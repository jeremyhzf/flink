/***********************************************************************************************************************
 *
 * Copyright (C) 2012 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/

package eu.stratosphere.pact.runtime.iterative.playing.simple;

import eu.stratosphere.nephele.configuration.Configuration;
import eu.stratosphere.nephele.configuration.GlobalConfiguration;
import eu.stratosphere.nephele.io.DistributionPattern;
import eu.stratosphere.nephele.io.channels.ChannelType;
import eu.stratosphere.nephele.jobgraph.JobGraph;
import eu.stratosphere.nephele.jobgraph.JobInputVertex;
import eu.stratosphere.nephele.jobgraph.JobOutputVertex;
import eu.stratosphere.nephele.jobgraph.JobTaskVertex;
import eu.stratosphere.pact.common.io.FileOutputFormat;
import eu.stratosphere.pact.common.io.TextInputFormat;
import eu.stratosphere.pact.runtime.iterative.playing.JobGraphUtils;
import eu.stratosphere.pact.runtime.iterative.playing.PlayConstants;
import eu.stratosphere.pact.runtime.iterative.task.IterationHeadPactTask;
import eu.stratosphere.pact.runtime.iterative.task.IterationIntermediatePactTask;
import eu.stratosphere.pact.runtime.iterative.task.IterationTailPactTask;
import eu.stratosphere.pact.runtime.shipping.ShipStrategy;
import eu.stratosphere.pact.runtime.task.MapDriver;
import eu.stratosphere.pact.runtime.task.util.TaskConfig;

public class Simple {

  public static void main(String[] args) throws Exception {

    int degreeOfParallelism = 2;
    int numSubTasksPerInstance = degreeOfParallelism;
    String inputPath = "file://" + PlayConstants.PLAY_DIR + "test-inputs/simple";
    String outputPath = "file:///tmp/stratosphere/iterations";
    String confPath = PlayConstants.PLAY_DIR + "local-conf";

    if (args.length == 5) {
      degreeOfParallelism = Integer.parseInt(args[0]);
      numSubTasksPerInstance = Integer.parseInt(args[1]);
      inputPath = args[2];
      outputPath = args[3];
      confPath = args[4];
    }

    JobGraph jobGraph = new JobGraph("SimpleIteration");

    JobInputVertex input = JobGraphUtils.createInput(TextInputFormat.class, inputPath, "FileInput", jobGraph,
        degreeOfParallelism, numSubTasksPerInstance);

    JobTaskVertex head = JobGraphUtils.createTask(IterationHeadPactTask.class, "BulkIterationHead", jobGraph,
        degreeOfParallelism, numSubTasksPerInstance);
    TaskConfig headConfig = new TaskConfig(head.getConfiguration());
    headConfig.setDriver(MapDriver.class);
    headConfig.setStubClass(AppendMapper.AppendHeadMapper.class);
    headConfig.setMemorySize(50 * JobGraphUtils.MEGABYTE);
    headConfig.setBackChannelMemoryFraction(0.8f);

    JobTaskVertex intermediate = JobGraphUtils.createTask(IterationIntermediatePactTask.class, "BulkIntermediate",
        jobGraph, degreeOfParallelism, numSubTasksPerInstance);
    TaskConfig intermediateConfig = new TaskConfig(intermediate.getConfiguration());
    intermediateConfig.setDriver(MapDriver.class);
    intermediateConfig.setStubClass(AppendMapper.AppendIntermediateMapper.class);
    intermediateConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, 1);

    JobTaskVertex tail = JobGraphUtils.createTask(IterationTailPactTask.class, "BulkIterationTail", jobGraph,
        degreeOfParallelism, numSubTasksPerInstance);
    TaskConfig tailConfig = new TaskConfig(tail.getConfiguration());
    tailConfig.setDriver(MapDriver.class);
    tailConfig.setStubClass(AppendMapper.AppendTailMapper.class);
    tailConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, 1);

    JobOutputVertex sync = JobGraphUtils.createSync(jobGraph, degreeOfParallelism);
    TaskConfig syncConfig = new TaskConfig(sync.getConfiguration());
    syncConfig.setNumberOfIterations(3);

    JobOutputVertex output = JobGraphUtils.createFileOutput(jobGraph, "FinalOutput", degreeOfParallelism,
        numSubTasksPerInstance);
    TaskConfig outputConfig = new TaskConfig(output.getConfiguration());
    outputConfig.setStubClass(SimpleOutFormat.class);
    outputConfig.setStubParameter(FileOutputFormat.FILE_PARAMETER_KEY, outputPath);

    //TODO implicit order should be documented/configured somehow
    JobOutputVertex fakeTailOutput = JobGraphUtils.createFakeOutput(jobGraph, "FakeTailOutput", degreeOfParallelism,
        numSubTasksPerInstance);

    JobGraphUtils.connect(input, head, ChannelType.INMEMORY, DistributionPattern.POINTWISE,
        ShipStrategy.ShipStrategyType.FORWARD);
    JobGraphUtils.connect(head, intermediate, ChannelType.NETWORK, DistributionPattern.POINTWISE,
        ShipStrategy.ShipStrategyType.FORWARD);
    JobGraphUtils.connect(head, sync, ChannelType.NETWORK, DistributionPattern.BIPARTITE,
        ShipStrategy.ShipStrategyType.FORWARD);
    JobGraphUtils.connect(head, output, ChannelType.INMEMORY, DistributionPattern.POINTWISE,
        ShipStrategy.ShipStrategyType.FORWARD);
    JobGraphUtils.connect(intermediate, tail, ChannelType.NETWORK, DistributionPattern.POINTWISE,
        ShipStrategy.ShipStrategyType.FORWARD);
    JobGraphUtils.connect(tail, fakeTailOutput, ChannelType.INMEMORY, DistributionPattern.POINTWISE,
        ShipStrategy.ShipStrategyType.FORWARD);

    input.setVertexToShareInstancesWith(head);
    intermediate.setVertexToShareInstancesWith(head);
    sync.setVertexToShareInstancesWith(head);
    output.setVertexToShareInstancesWith(head);
    intermediate.setVertexToShareInstancesWith(head);
    tail.setVertexToShareInstancesWith(head);
    fakeTailOutput.setVertexToShareInstancesWith(head);

    GlobalConfiguration.loadConfiguration(confPath);
    Configuration conf = GlobalConfiguration.getConfiguration();

    JobGraphUtils.submit(jobGraph, conf);
  }


}

