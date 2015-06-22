/**
 *  Copyright 2012 LiveRamp
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.liveramp.cascading_ext.counters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.mapred.RunningJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cascading.flow.Flow;
import cascading.stats.FlowStats;
import cascading.stats.FlowStepStats;
import cascading.stats.hadoop.HadoopStepStats;
import cascading.tap.Tap;

import com.liveramp.commons.collections.nested_map.ThreeNestedMap;
import com.liveramp.commons.collections.nested_map.TwoNestedMap;

/**
 * Use these helper methods to safely retrieve Hadoop counters.  Sometimes counters
 * get pushed off the job tracker too quickly so when we try to retrieve a missing
 * counter we get a NPE which kills the process.  At this point the counter is gone
 * so we might as well keep going.
 */
public class Counters {
  private static final Logger LOG = LoggerFactory.getLogger(Counters.class);

  /**
   * Get all counters for a given flow.  It returns a map keyed on the step stats object to
   * a list of all the counter objects for that step
   */
  public static Map<FlowStepStats, List<Counter>> getCountersByStep(Flow flow) {
    FlowStats flowStats = flow.getFlowStats();

    Map<FlowStepStats, List<Counter>> counters = new HashMap<>();

    for (FlowStepStats statsForStep : flowStats.getFlowStepStats()) {
      if (!counters.containsKey(statsForStep)) {
        counters.put(statsForStep, Lists.<Counter>newArrayList());
      }
      counters.get(statsForStep).addAll(getStatsFromStep(statsForStep));
    }

    for (Map.Entry<FlowStepStats, List<Counter>> entry : counters.entrySet()) {
      Collections.sort(entry.getValue());
    }

    return counters;
  }

  public static Long get(Flow flow, String group, String value) throws IOException {
    FlowStats stats = flow.getFlowStats();

    long total = 0;
    for (FlowStepStats step : stats.getFlowStepStats()) {
      total += get(step, group, value);
    }
    return total;

  }

  public static Long get(Flow flow, Enum value) throws IOException {
    FlowStats stats = flow.getFlowStats();

    long total = 0;
    for (FlowStepStats step : stats.getFlowStepStats()) {
      total += get(step, value);
    }
    return total;

  }

  public static List<Counter> getCounters(Flow flow) {
    return getCountersForGroup(flow.getFlowStats());
  }

  public static List<Counter> getCountersForGroup(Flow flow, String group) throws IOException {
    return getCountersForGroup(flow.getFlowStats(), group);
  }

  public static Long get(FlowStepStats step, Enum value) throws IOException {
    if (step instanceof HadoopStepStats) {
      HadoopStepStats hadoopStep = (HadoopStepStats)step;
      return hadoopStep.getRunningJob().getCounters().getCounter(value);
    } else {
      return step.getCounterValue(value);
    }
  }

  public static void printCounters(Flow flow) {
    System.out.println(prettyCountersString(flow));
  }

  public static String prettyCountersString(RunningJob job) {
    StringBuilder builder = new StringBuilder("\n").append(StringUtils.repeat("=", 90)).append("\n");
    builder.append("Counters for job ").append(job.getJobName()).append("\n");

    for (Counter counter : getStatsFromRunningJob(job)) {
      if (counter.getValue() != null && counter.getValue() > 0) {
        builder.append("    ").append(counter).append("\n");
      }
    }
    builder.append(StringUtils.repeat("=", 90)).append("\n");
    return builder.toString();
  }

  public static String prettyCountersString(Flow<?> flow) {
    Map<FlowStepStats, List<Counter>> counters = Counters.getCountersByStep(flow);
    StringBuilder builder = new StringBuilder("\n").append(StringUtils.repeat("=", 90)).append("\n");

    builder.append("Counters for ").append(flow.getName() == null ? "unnamed flow" : "flow " + flow.getName()).append("\n")
        .append("  with input ").append(prettyTaps(flow.getSources())).append("\n")
        .append("  and output ").append(prettyTaps(flow.getSinks())).append("\n");

    for (Map.Entry<FlowStepStats, List<Counter>> entry : counters.entrySet()) {
      builder.append("  Step: ").append(entry.getKey().getName()).append("\n");

      if (entry.getValue().isEmpty()) {
        builder.append("    No counters found.\n");
        continue;
      }

      boolean anyTuplesRead = false;
      boolean anyTuplesWritten = false;
      for (Counter counter : entry.getValue()) {
        if (counter.getValue() != null && counter.getValue() > 0) {
          builder.append("    ").append(counter).append("\n");

          if (counter.getName().equals("Tuples_Read")) {
            anyTuplesRead = true;
          }
          if (counter.getName().equals("Tuples_Written")) {
            anyTuplesWritten = true;
          }
        }
      }

      if (anyTuplesRead && !anyTuplesWritten) {
        builder.append("  *** BLACK HOLE WARNING *** The above step had input but no output\n");
      }
    }
    builder.append(StringUtils.repeat("=", 90)).append("\n");
    return builder.toString();
  }

  public static TwoNestedMap<String, String, Long> getCounterMap(RunningJob job) {
    TwoNestedMap<String, String, Long> counterMap = new TwoNestedMap<>();

    try {
      org.apache.hadoop.mapred.Counters allCounters = job.getCounters();

      if (allCounters != null) {
        Collection<String> groupNames = allCounters.getGroupNames();

        for (String groupName : groupNames) {
          org.apache.hadoop.mapred.Counters.Group group = allCounters.getGroup(groupName);
          for (org.apache.hadoop.mapred.Counters.Counter counter : group) {
            counterMap.put(groupName, counter.getName(), counter.getValue());
          }
        }
      } else {
        LOG.error("Could not retrieve counters from job " + job.getID());
      }

    } catch (Exception e) {
      LOG.error("Error getting counters!", e);
    }

    return counterMap;
  }

  public static ThreeNestedMap<String, String, String, Long> getCounterMap(FlowStats stats) {

    ThreeNestedMap<String, String, String, Long> counters = new ThreeNestedMap<>();

    for (FlowStepStats step : stats.getFlowStepStats()) {
      try {
        if (step instanceof HadoopStepStats) {
          HadoopStepStats hdStepStats = (HadoopStepStats)step;
          String jobId = hdStepStats.getJobID();

          for (String currentGroup : safeGetCounterGroups(step)) {
            for (String name : step.getCountersFor(currentGroup)) {
              counters.put(jobId, currentGroup, name, step.getCounterValue(currentGroup, name));
            }
          }

        }
      } catch (NullPointerException e) {
        LOG.error("NullPointerException getting counters: ", e);
      }

    }

    return counters;
  }

  //  internal only methods

  private static Long get(FlowStepStats step, String group, String value) throws IOException {
    if (step instanceof HadoopStepStats) {
      HadoopStepStats hadoopStep = (HadoopStepStats)step;
      org.apache.hadoop.mapred.Counters.Group counterGroup = hadoopStep.getRunningJob().getCounters().getGroup(group);
      if (counterGroup != null) {
        return counterGroup.getCounter(value);
      }
      LOG.info("Counter " + group + ":" + value + " not set.");
      return 0l;
    } else {
      return step.getCounterValue(group, value);
    }
  }

  private static List<Counter> getStatsFromStep(FlowStepStats statsForStep) {
    if (statsForStep instanceof HadoopStepStats) {
      return getStatsFromRunningJob(((HadoopStepStats)statsForStep).getRunningJob());
    } else {
      return getStatsFromGenericStep(statsForStep, null);
    }
  }

  private static List<Counter> getStatsFromStep(FlowStepStats statsForStep, String group) throws IOException {
    if (statsForStep instanceof HadoopStepStats) {
      return getStatsFromRunningJob(((HadoopStepStats)statsForStep).getRunningJob(), group);
    } else {
      return getStatsFromGenericStep(statsForStep, group);
    }
  }

  private static List<Counter> getStatsFromGenericStep(FlowStepStats step, String group) {
    List<Counter> counters = new ArrayList<>();
    for (String currentGroup : safeGetCounterGroups(step)) {
      if (group == null || group.equals(currentGroup)) {
        for (String name : step.getCountersFor(currentGroup)) {
          counters.add(new Counter(currentGroup, name, step.getCounterValue(currentGroup, name)));
        }
      }
    }
    return counters;
  }

  private static Collection<String> safeGetCounterGroups(FlowStepStats stats) {
    try {
      return stats.getCounterGroups();
    } catch (Exception e) {
      LOG.error("Error getting counter groups: ", e);
      return Collections.emptyList();
    }
  }

  private static List<Counter> getCountersForGroup(FlowStats flowStats) {
    List<Counter> counters = new ArrayList<>();
    for (FlowStepStats step : flowStats.getFlowStepStats()) {
      counters.addAll(getStatsFromStep(step));
    }
    Collections.sort(counters);
    return counters;
  }

  private static List<Counter> getCountersForGroup(FlowStats flowStats, String group) throws IOException {
    List<Counter> counters = new ArrayList<>();
    for (FlowStepStats step : flowStats.getFlowStepStats()) {
      counters.addAll(getStatsFromStep(step, group));
    }
    Collections.sort(counters);
    return counters;
  }

  private static List<Counter> getStatsFromRunningJob(RunningJob job) {
    try {
      org.apache.hadoop.mapred.Counters allCounters = job.getCounters();

      List<Counter> counters = new ArrayList<>();
      for (String group : allCounters.getGroupNames()) {
        counters.addAll(getAllFromHadoopGroup(allCounters.getGroup(group)));
      }

      return counters;
    } catch (Exception e) {
      LOG.error("Error getting counters from job", e);
      return Collections.emptyList();
    }
  }

  private static List<Counter> getStatsFromRunningJob(RunningJob job, String groupToSearch) throws IOException {
      return getAllFromHadoopGroup(job.getCounters().getGroup(groupToSearch));
  }

  private static List<Counter> getAllFromHadoopGroup(org.apache.hadoop.mapred.Counters.Group counterGroup) {
    Iterator<org.apache.hadoop.mapred.Counters.Counter> counterIterator = counterGroup.iterator();
    List<Counter> counters = new ArrayList<>();

    while (counterIterator.hasNext()) {
      org.apache.hadoop.mapred.Counters.Counter counter = counterIterator.next();
      counters.add(new Counter(counterGroup.getName(), counter.getName(), counter.getValue()));
    }

    return counters;
  }

  private static String prettyTaps(Map<String, Tap> taps) {
    if (taps.keySet().isEmpty()) {
      return "[]";
    }

    Collection<Tap> values = taps.values();
    Tap first = values.toArray(new Tap[values.size()])[0];
    if (first == null) {
      return "[null tap]";
    }

    if (taps.keySet().size() == 1) {
      return "[\"" + first.getIdentifier() + "\"]";
    } else {
      return "[\"" + first.getIdentifier() + "\",...]";
    }
  }
}
