package org.deeplearning4j.optimize.solvers.accumulation;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.exception.DL4JInvalidConfigException;
import org.deeplearning4j.optimize.api.StepFunction;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.api.memory.enums.AllocationPolicy;
import org.nd4j.linalg.api.memory.enums.LearningPolicy;
import org.nd4j.linalg.api.memory.enums.ResetPolicy;
import org.nd4j.linalg.api.memory.enums.SpillPolicy;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.exception.ND4JIllegalStateException;
import org.nd4j.linalg.factory.Nd4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This GradientsAccumulator is suited for CUDA backend.
 *
 * @author raver119@gmail.com
 */
@Slf4j
public class CudaGradientsAccumulator implements GradientsAccumulator, Registerable{
    protected ThreadLocal<INDArray> accumulator = new ThreadLocal<>();

    protected int parties;
    protected MessageHandler handler;
    protected List<BlockingQueue<INDArray>> messages = new ArrayList<>();
    protected List<MemoryWorkspace> workspaces = new ArrayList<>();
    protected List<ReentrantLock> locks = new ArrayList<>();

    protected AtomicInteger workersCounter = new AtomicInteger(0);
    protected ThreadLocal<Integer> index = new ThreadLocal<>();
    protected long initialMemory = 100 * 1024 * 1024L;
    protected int queueSize = 5;
    protected Double boundary = 1.0;

    protected Queue<INDArray> externalSource;

    protected AtomicBoolean isFirst = new AtomicBoolean(false);
    protected AtomicBoolean isDone = new AtomicBoolean(true);

    protected AtomicInteger barrier = new AtomicInteger(0);
    protected AtomicInteger secondary = new AtomicInteger(0);
    protected AtomicBoolean registered = new AtomicBoolean(false);
    protected AtomicBoolean bypassMode = new AtomicBoolean(false);
    protected final AtomicInteger currentConsumers = new AtomicInteger(0);


    public CudaGradientsAccumulator(double parties) {
        this(Nd4j.getAffinityManager().getNumberOfDevices(), 1e-3);
    }

    // TODO: delete this one maybe?
    public CudaGradientsAccumulator(int parties) {
        this(parties, 1e-3);
    }

    public CudaGradientsAccumulator(int parties, double threshold) {
        this(parties, new EncodingHandler(threshold), 100 * 1024 * 1024L, 10, 1.0);
    }

    protected CudaGradientsAccumulator(int parties, @NonNull MessageHandler handler, long initialMemory, int queueSize, Double boundary) {
        this.parties = parties;
        this.handler = handler;
        this.initialMemory = initialMemory;
        this.queueSize = queueSize;
        this.boundary = boundary;

        // maybe not the best idea in the world, but we'll use cyclic workspace of 25MB to receive updates
        WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                .initialSize(initialMemory)
                .policyReset(ResetPolicy.ENDOFBUFFER_REACHED)
                .policyAllocation(AllocationPolicy.STRICT)
                .policySpill(SpillPolicy.FAIL)
                .policyLearning(LearningPolicy.NONE)
                .build();

        int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();

        // we are going to take single-device systems as edge case: cpu & small models at single-gpu systems.
        if (parties > numDevices && numDevices != 1)
            throw new ND4JIllegalStateException("Number of parties ["+ parties +"] should be less or equal to number of devices [" + numDevices + "]");

        // pre-create Queues for local workers
        int curDev = Nd4j.getAffinityManager().getDeviceForCurrentThread();

        for (int i = 0; i < parties; i++) {
            messages.add(new LinkedBlockingQueue<INDArray>(queueSize));

            // we don't want device index to step out of boundaries here
            int cDevice = numDevices > 1 ? i % numDevices : 0;

            Nd4j.getAffinityManager().unsafeSetDevice(cDevice);
            MemoryWorkspace ws = Nd4j.getWorkspaceManager().createNewWorkspace(configuration,"CGA-" + i, cDevice);
            //ws.enableDebug(true);
            workspaces.add(ws);

            locks.add(new ReentrantLock());
        }
        Nd4j.getAffinityManager().unsafeSetDevice(curDev);

        handler.initialize(this);
    }

    @Override
    public void fallbackToSingleConsumerMode(boolean reallyFallback) {
        if (externalSource != null && externalSource instanceof Registerable)
            ((Registerable) externalSource).fallbackToSingleConsumerMode(reallyFallback);

        bypassMode.set(reallyFallback);
    }

    @Override
    public void registerConsumers(int numConsumers) {
        // we don't want double spending here
        if (registered.get()) {
            log.info("Master thread locks at RC");
            while (registered.get()) {
                LockSupport.parkNanos(100L);
            }
            log.info("Master thread unlocks at RC");
        }

        // we're passing number of consumers for current session to externalSource, if applicable
        if (externalSource != null && externalSource instanceof Registerable)
            ((Registerable) externalSource).registerConsumers(numConsumers);

        currentConsumers.set(numConsumers);
        registered.set(true);
    }

    protected void synchronize(int consumers) {
        if (consumers == 1 || bypassMode.get())
            return;

        log.info("thread {} locking at CGA", Thread.currentThread().getId());

        // any first thread entering this block - will reset this field to false
        isDone.compareAndSet(true, false);

        // last thread will set isDone to true
        if (barrier.incrementAndGet() == currentConsumers.get()) {
            secondary.set(0);
            barrier.set(0);
            isFirst.set(false);
            isDone.set(true);
        } else {
            // just wait, till last thread will set isDone to true
            while (!isDone.get())
                LockSupport.parkNanos(1000L);
        }

        // second lock here needed only to ensure we won't get overrun over isDone flag
        if (secondary.incrementAndGet() == currentConsumers.get()) {
            isFirst.set(true);
            registered.set(false);
        } else {
            while (!isFirst.get())
                LockSupport.parkNanos(1000L);
        }

        log.info("thread {} unlocking at CGA", Thread.currentThread().getId());

    }

    /**
     * This method applies accumulated updates via given StepFunction
     *
     * @param function
     * @param params
     */
    @Override
    public void applyUpdate(StepFunction function, INDArray params, INDArray updates) {
        // nullify given updates first
        updates.assign(0.0f);

        int cnt = 0;
        while (!messages.get(index.get()).isEmpty()) {
            INDArray compressed = messages.get(index.get()).poll();

            INDArray decoded = Nd4j.getExecutioner().thresholdDecode(compressed, updates);
            cnt++;
        }

        if (cnt > 0)
            log.info("Local updates to be applied: {}", cnt);

        if (externalSource != null) {
            int ent = 0;
            while (!externalSource.isEmpty()) {
                INDArray compressed = externalSource.poll();

                INDArray decoded = Nd4j.getExecutioner().thresholdDecode(compressed, updates);
                cnt++;
                ent++;
            }
            log.info("thread {} finished at Externals", Thread.currentThread().getId());

            if (ent > 0)
                log.info("External updates to be applied: {}", ent);
        }

        // TODO: average updates probably?

        if (cnt > 0)
            function.step(params, updates);
    }

    /**
     * This method applies accumulated updates via given StepFunction
     *
     * @param function
     * @param params
     * @param alpha
     */
    @Override
    public void applyUpdate(StepFunction function, INDArray params, INDArray updates, double alpha) {
        // nullify given updates first
        updates.assign(0.0f);

        int cnt = 0;
        while (!messages.get(index.get()).isEmpty()) {
            INDArray compressed = messages.get(index.get()).poll();

            INDArray decoded = Nd4j.getExecutioner().thresholdDecode(compressed, updates);
            cnt++;
        }

        if (cnt > 0)
            log.info("Local updates to be applied: {}", cnt);

        if (externalSource != null) {
            int ent = 0;
            while (!externalSource.isEmpty()) {
                INDArray compressed = externalSource.poll();

                INDArray decoded = Nd4j.getExecutioner().thresholdDecode(compressed, updates);
                cnt++;
                ent++;
            }

            if (ent > 0)
                log.info("External updates to be applied: {}", ent);
        }

        // TODO: average updates? might have sense

        if (cnt > 0)
            function.step(params, updates, alpha);
    }

    /**
     * This method allows to pass external updates to accumulator, they will be populated across all workers using this GradientsAccumulator instance
     *
     * @param source
     */
    @Override
    public void setExternalSource(Queue<INDArray> source) {
        this.externalSource = source;
    }

    /**
     * This method does initialization of given worker wrt Thread-Device Affinity
     */
    @Override
    public void touch() {
        if (index.get() == null) {
            // set index
            int numDevces = Nd4j.getAffinityManager().getNumberOfDevices();

            /*
                if we have > 1 computational device, we assign workers to workspaces "as is", as provided via AffinityManager
             */
            if (numDevces > 1 && parties > 1) {
                int localIndex = Nd4j.getAffinityManager().getDeviceForCurrentThread();

                index.set(localIndex);
            } else {
                // if we have only 1 device (like cpu system, or single gpu), just attach consumer via flat index
                index.set(workersCounter.getAndIncrement());
            }
        }
    }

    /**
     * This method accepts updates suitable for StepFunction, and accumulates/propagates it across all workers
     *
     * @param array
     */
    @Override
    public void storeUpdate(INDArray array) {
        if (accumulator.get() == null) {
            // we don't want accumulator to be attached to workspaces
            try (MemoryWorkspace workspace = Nd4j.getMemoryManager().scopeOutOfWorkspaces()) {
                accumulator.set(Nd4j.create(array.shape(), array.ordering()));
            }
        }


        log.info("thread {} locking at Register", Thread.currentThread().getId());

        // block until ParallelWrapper sends us message about number of threads in this cycle
        if (!bypassMode.get())
            while (!registered.get())
                LockSupport.parkNanos(100L);


        log.info("thread {} unlocking at Register", Thread.currentThread().getId());

        // accumulate gradients updates in residental array
        accumulator.get().addi(array);

        // propagate changes & modify accumulator
        handler.broadcastUpdates(accumulator.get());

        // we're blocking here, untill all done broadcasting updates
        synchronize(currentConsumers.get());
    }

    /**
     * This method accepts updates suitable for StepFunction and puts them to the queue, which is used in backpropagation loop
     * <p>
     * PLEASE NOTE: array is expected to be ready for use and match params dimensionality
     *
     * @param array
     */
    @Override
    public void receiveUpdate(INDArray array) {
        // we're replicating COMPRESSED MESSAGES, decompression will be thread-local
        for (int i = 0; i < parties; i++) {
            // we don't want to have same workspace to be accessible by 2 different threads for now
            /*
                With synchronized external data, it's impossible to deadlock here.
                Each worker is guaranteed to have at least NUM_WORKERS slots in buffer.
                So we use this lock just to ensure thread-safety of corresponding workspaces
             */
            locks.get(i).lock();

            try (MemoryWorkspace workspace = workspaces.get(i).notifyScopeEntered()) {
                // we might just scope out of workspace here, instead of throwing error out
                if (array.data().length() > (initialMemory / queueSize) / Nd4j.sizeOfDataType(array.data().dataType()))
                    throw new ND4JIllegalStateException("Not enough memory to handle update: ["+ array.data().length() * Nd4j.sizeOfDataType(array.data().dataType())+" bytes required]. Please increase memory amount for GradientsAccumulator");

                INDArray compressed = array.unsafeDuplication();
                try {
                    messages.get(i).put(compressed);
                } catch (InterruptedException e) {
                    log.info("Something bad at index_{}", i);
                    throw new RuntimeException(e);
                }
            }

            locks.get(i).unlock();
        }
    }

    /**
     * This method resets all accumulated updates (if any)
     */
    @Override
    public void reset() {
        // just replace accumulator, gc will do the rest
        accumulator = new ThreadLocal<>();

        // resetting this counter too
        workersCounter.set(0);

        // throw away message queues
        for (int i = 0; i < parties; i++) {
            messages.get(i).clear();
        }
    }

    /**
     * This method returns number of free slots for updates
     *
     * @param worker
     * @return
     */
    @Override
    public int getFreeSpace(int worker) {
        int currSize = messages.get(worker).size();
        return queueSize - currSize - workersCounter.get();
    }

    public static class Builder {
        protected int parties;
        protected double threshold = 1e-3;
        protected long initialMemory = 100 * 1024 * 1024L;
        protected int queueSize = 5;
        protected MessageHandler handler;
        protected Double boundary = null;

        /**
         * This
         * @param parties
         */
        public Builder(int parties) {
            if (parties < 1)
                throw new DL4JInvalidConfigException("Number of parties for GradientsAccumulation should be positive value");

            this.parties = parties;
        }

        /**
         * This method allows to specify MessageHandler instance
         *
         * Default value: EncodingHandler
         * @param handler
         * @return
         */
        public Builder messageHandler(@NonNull MessageHandler handler) {
            this.handler = handler;
            return this;
        }

        /**
         * This method allows to set encoding threshold for this accumulator instance
         *
         * Default value: 1e-3
         * @param threshold
         * @return
         */
        public Builder encodingThreshold(double threshold) {
            this.threshold = threshold;
            return this;
        }

        /**
         * This method enables optional limit for max number of updates per message
         *
         * Default value: 1.0 (no limit)
         * @param boundary positive value in range 0..1
         * @return
         */
        public Builder updatesBoundary(double boundary) {
            if (boundary >= 1.0)
                return this;

            if (boundary <= 0.0)
                throw new DL4JInvalidConfigException("Boundary should have positive value");

            this.boundary = boundary;
            return this;
        }


        /**
         * This method allows to define buffer memory parameters for this GradientsAccumulator
         *
         * Default values: 100MB initialMemory, 5 queueSize
         * @param initialMemory
         * @param queueSize
         * @return
         */
        public Builder memoryParameters(long initialMemory, int queueSize) {
            this.initialMemory = initialMemory;
            this.queueSize = queueSize;
            return this;
        }

        public CudaGradientsAccumulator build() {
            if (handler == null) {
                if (boundary == null)
                    handler = new EncodingHandler(threshold);
                else
                    handler = new EncodingHandler(threshold, boundary);
            }

            CudaGradientsAccumulator accumulator = new CudaGradientsAccumulator(parties, handler, initialMemory, queueSize, boundary);

            return accumulator;
        }
    }
}
