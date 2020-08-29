package solution;

import data.Schedule;
import data.Task;
import data.TaskGraph;
import solution.helpers.PartialSchedule;
import solution.helpers.PreProcessor;

import java.util.*;
import java.util.concurrent.*;

public class SolutionParallel extends Solution {
    private ForkJoinPool forkJoinPool;

    public void setNumCores(int numCores) {
        this.forkJoinPool = new ForkJoinPool(numCores);
    }

    /**
     * Creates an optimal scheduling of tasks on specified number of processors.
     *
     * @param taskGraph      Graph containing tasks as nodes and their dependencies as edges.
     * @param numProcessors  Number of processors to schedule the tasks on.
     * @param upperBoundTime Upper bound of running time that the optimal solution should do at least as good as.
     * @return optimal schedule found by the run method.
     */
    public Schedule run(TaskGraph taskGraph, int numProcessors, int upperBoundTime) {
        initializeGlobalVars(taskGraph, numProcessors, upperBoundTime);
        SolutionState initialSolutionState = initializeState(taskGraph, numProcessors);

        RecursiveSearch recursiveSearch = new RecursiveSearch(initialSolutionState);
        forkJoinPool.invoke(recursiveSearch);

        setDone();
        return createOutput();
    }

    private class RecursiveSearch extends RecursiveAction {
        private SolutionState solutionState;

        private RecursiveSearch(SolutionState solutionState) {
            this.solutionState = solutionState;
        }

        /**
         * Recursively try to schedule a task on a processor.
         * Uses DFS to try all possible schedules.
         */
        @Override
        protected void compute() {
            updateStateCount();

            // Base case is when queue is empty, i.e. all tasks scheduled.
            if (solutionState.candidateTasks.isEmpty()) {
                int finishTime = findMaxInArray(solutionState.processorFinishTimes);

                synchronized (this) {
                    //If schedule time is better, update bestFinishTime and best schedule
                    if (finishTime < bestFinishTime) {
                        bestFinishTime = finishTime;

                        for (int i = 0; i < bestStartTime.length; i++) {
                            bestScheduledOn[i] = solutionState.scheduledOn[i];
                            bestStartTime[i] = solutionState.taskStartTimes[i];
                        }
                        updateBestScheduleOnVisual();
                    }
                }
                return;
            }

            // Create a hash code for our partial schedule to check whether we have examined an equivalent schedule before
            // If we have seen an equivalent schedule we do not need to proceed
            int hashCode = PartialSchedule.generateHashCode(solutionState.taskStartTimes, solutionState.scheduledOn, numProcessors);
            synchronized (this) {
                if (seenSchedules.contains(hashCode)) {
                    return;
                } else {
                    seenSchedules.add(hashCode);
                }
            }

            // Information we need about the current schedule
            // minimal remaining time IF all remaining tasks are evenly distributed amongst processors.
            int loadBalancedRemainingTime = (int) Math.ceil(solutionState.remainingDuration / (double) numProcessors);

            int earliestProcessorFinishTime = Integer.MAX_VALUE;
            int latestProcessorFinishTime = 0;
            for (int l = 0; l < numProcessors; l++) {
                earliestProcessorFinishTime = Math.min(solutionState.processorFinishTimes[l], earliestProcessorFinishTime);
                latestProcessorFinishTime = Math.max(solutionState.processorFinishTimes[l], latestProcessorFinishTime);
            }

            int longestCriticalPath = 0;
            for (int task : solutionState.candidateTasks) {
                int criticalPath = maxLengthToExitNode[task];
                if (criticalPath > longestCriticalPath) {
                    longestCriticalPath = criticalPath;
                }
            }

            // Iterate through tasks
            solutionState.candidateTasks.sort(Comparator.comparingInt(a -> nodePriorities[a]));
            HashSet<Integer> seenTasks = new HashSet<>();
            for (int i = 0; i < solutionState.candidateTasks.size(); i++) {
                List<RecursiveSearch> executableList = new ArrayList<>();

                int candidateTask = solutionState.candidateTasks.remove();
                if (seenTasks.contains(candidateTask)) {
                    solutionState.candidateTasks.add(candidateTask);
                    continue;
                } else {
                    ArrayList<Integer> equivalentNodes = equivalentNodesList[candidateTask];
                    seenTasks.addAll(equivalentNodes);
                }

                // Exit conditions 1
                boolean loadBalancingConstraint;
                boolean criticalPathConstraint;
                boolean latestFinishTimeConstraint;
                synchronized (this) {
                    loadBalancingConstraint = earliestProcessorFinishTime + loadBalancedRemainingTime >= bestFinishTime;
                    criticalPathConstraint = earliestProcessorFinishTime + longestCriticalPath >= bestFinishTime;
                    latestFinishTimeConstraint = latestProcessorFinishTime >= bestFinishTime;
                }
                if (loadBalancingConstraint || criticalPathConstraint || latestFinishTimeConstraint) {
                    solutionState.candidateTasks.add(candidateTask);
                    continue;
                }

                // Update state (Location 1: Candidate data.Task)
                solutionState.remainingDuration -= taskGraph.getDuration(candidateTask);
                List<Integer> candidateChildren = taskGraph.getChildrenList(candidateTask);
                for (Integer candidateChild : candidateChildren) {
                    solutionState.inDegrees[candidateChild]--;
                    if (solutionState.inDegrees[candidateChild] == 0) {
                        solutionState.candidateTasks.add(candidateChild);
                    }
                }

                // Calculate information we need about constraints due to communication costs
                int maxDataArrival = 0;
                int processorCausingMaxDataArrival = 0;
                int secondMaxDataArrival = 0;
                List<Integer> parents = taskGraph.getParentsList(candidateTask);
                for (int parent : parents) {
                    int dataArrival = solutionState.taskStartTimes[parent] + taskGraph.getDuration(parent) + taskGraph.getCommCost(parent, candidateTask);
                    if (dataArrival >= maxDataArrival) {
                        if (solutionState.scheduledOn[parent] != processorCausingMaxDataArrival) {
                            secondMaxDataArrival = maxDataArrival;
                        }
                        maxDataArrival = dataArrival;
                        processorCausingMaxDataArrival = solutionState.scheduledOn[parent];

                    } else if (dataArrival >= secondMaxDataArrival) {
                        if (solutionState.scheduledOn[parent] != processorCausingMaxDataArrival) {
                            secondMaxDataArrival = dataArrival;
                        }
                    }
                }

                boolean hasBeenScheduledAtStart = false;
                for (int candidateProcessor = 0; candidateProcessor < numProcessors; candidateProcessor++) { // Iterate through processors
                    // Avoid processor isomorphism
                    if (solutionState.processorFinishTimes[candidateProcessor] == 0) {
                        if (hasBeenScheduledAtStart) {
                            // Skip duplicated search space
                            continue;
                        } else {
                            hasBeenScheduledAtStart = true;
                        }
                    }

                    // Find earliest time to schedule candidate task on candidate processor
                    int earliestStartTimeOnCurrentProcessor = solutionState.processorFinishTimes[candidateProcessor];
                    if (processorCausingMaxDataArrival != candidateProcessor) {
                        earliestStartTimeOnCurrentProcessor = Math.max(earliestStartTimeOnCurrentProcessor, maxDataArrival);
                    } else {
                        earliestStartTimeOnCurrentProcessor = Math.max(earliestStartTimeOnCurrentProcessor, secondMaxDataArrival);
                    }

                    // Exit conditions 2: tighter constraint now that we have selected the processor
                    synchronized (this) {
                        criticalPathConstraint = earliestStartTimeOnCurrentProcessor + maxLengthToExitNode[candidateTask] >= bestFinishTime;
                    }
                    if (criticalPathConstraint) {
                        continue;
                    }

                    // Update state (Location 2: Processors)
                    int prevFinishTime = solutionState.processorFinishTimes[candidateProcessor];
                    solutionState.processorFinishTimes[candidateProcessor] = earliestStartTimeOnCurrentProcessor + taskGraph.getDuration(candidateTask);
                    solutionState.scheduledOn[candidateTask] = candidateProcessor;
                    solutionState.taskStartTimes[candidateTask] = earliestStartTimeOnCurrentProcessor;

                    RecursiveSearch recursiveSearch;
                    recursiveSearch = new RecursiveSearch(solutionState.getDeepCopy());
                    executableList.add(recursiveSearch);

                    // Backtrack state (Location 2: Processors)
                    solutionState.processorFinishTimes[candidateProcessor] = prevFinishTime;
                }

                // Backtrack state (Location 1: Candidate data.Task)
                for (Integer candidateChild : candidateChildren) {
                    // revert changes made to children
                    solutionState.inDegrees[candidateChild]++;
                    if (solutionState.inDegrees[candidateChild] == 1) {
                        solutionState.candidateTasks.removeLast();
                    }
                }
                solutionState.remainingDuration += taskGraph.getDuration(candidateTask);
                solutionState.candidateTasks.add(candidateTask);
                solutionState.taskStartTimes[candidateTask] = -1;
                ForkJoinTask.invokeAll(executableList);
            }
        }

    }

    /**
     * Helper method to create the output data.Schedule.
     *
     * @return Optimal data.Schedule.
     */
    private Schedule createOutput() {
        Task[] optimalSchedule = new Task[numTasks];
        for (int i = 0; i < numTasks; i++) {
            Task t = new Task(i, bestStartTime[i],
                    bestStartTime[i] + taskGraph.getDuration(i), bestScheduledOn[i]);
            optimalSchedule[i] = t;
        }

        return new Schedule(optimalSchedule, bestFinishTime);
    }

    /**
     * Find the maximum value integer in the array. Returns Integer.MIN_VALUE if array is empty.
     *
     * @return maximum value.
     */
    private int findMaxInArray(int[] arr) {
        int max = Integer.MIN_VALUE;
        for (int j : arr) {
            max = Math.max(max, j);
        }

        return max;
    }

    /**
     * Helper method to initialize variables used by all threads.
     */
    private void initializeGlobalVars(TaskGraph taskGraph, int numProcessors, int upperBoundTime) {
        this.taskGraph = taskGraph;
        this.numProcessors = numProcessors;
        maxLengthToExitNode = PreProcessor.maxLengthToExitNode(taskGraph);
        nodePriorities = maxLengthToExitNode;
        bestFinishTime = upperBoundTime;
        updateBestScheduleOnVisual();
        numTasks = taskGraph.getNumberOfTasks();
        equivalentNodesList = PreProcessor.getNodeEquivalence(taskGraph);
        bestStartTime = new int[numTasks];
        bestScheduledOn = new int[numTasks];
    }

    /**
     * Helper method to create the initial state on which the algorithm runs.
     */
    private SolutionState initializeState(TaskGraph taskGraph, int numProcessors) {
        LinkedList<Integer> candidateTasks = new LinkedList<>();
        int[] inDegrees = new int[numTasks];
        int[] taskStartTimes = new int[numTasks];
        Arrays.fill(taskStartTimes, -1);
        int[] scheduledOn = new int[numTasks];
        int[] processorFinishTimes = new int[numProcessors];
        int remainingDuration = 0;
        for (int i = 0; i < numTasks; i++) {
            // calculate remaining duration of tasks to be scheduled
            remainingDuration += taskGraph.getDuration(i);
            inDegrees[i] = taskGraph.getParentsList(i).size();
            if (inDegrees[i] == 0) {
                candidateTasks.add(i);
            }
        }
        return new SolutionState(candidateTasks, inDegrees, taskStartTimes,
                scheduledOn, processorFinishTimes, remainingDuration);
    }
}