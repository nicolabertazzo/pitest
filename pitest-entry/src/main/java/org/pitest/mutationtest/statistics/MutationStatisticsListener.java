/*
 * Copyright 2011 Henry Coles
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.pitest.mutationtest.statistics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.pitest.mutationtest.ClassMutationResults;
import org.pitest.mutationtest.DetectionStatus;
import org.pitest.mutationtest.MutationResult;
import org.pitest.mutationtest.MutationResultListener;
import org.pitest.util.Unchecked;

public class MutationStatisticsListener implements MutationResultListener,
MutationStatisticsSource {

  private final MutationStatisticsPrecursor mutatorScores = new MutationStatisticsPrecursor();
  private List<ClassMutationResults> classMutationResultsList = new ArrayList<ClassMutationResults>();
  public static final String TESTED = "TESTED";
  public static final String NOT_COVERED = "NOT_COVERED";
  public static final String PSEUDO_TESTED = "PSEUDO_TESTED";
  public static final String PARTIALLY_TESTED = "PARTIALLY_TESTED";

  @Override
  public MutationStatistics getStatistics() {
    handleMethodClassification(classMutationResultsList);
    return this.mutatorScores.toStatistics();
  }

  @Override
  public void runStart() {

  }

  @Override
  public void handleMutationResult(final ClassMutationResults metaData) {
    processMetaData(metaData);
    classMutationResultsList.add(metaData);
  }

  @Override
  public void runEnd() {

  }

  private void processMetaData(final ClassMutationResults value) {
    this.mutatorScores.registerResults(value.getMutations());
  }

  private void handleMethodClassification(List<ClassMutationResults> results) {
      Map<String, Integer> methodsStats = new HashMap<String, Integer>();
      Integer tested = 0;
      Integer notCovered = 0;
      Integer psedudoTested = 0;
      Integer partiallyTested = 0;
      try {
          for (List<MutationResult> methodResults : aggregateMethods(results).values()) {
              switch (classifyMethod(methodResults)) {
              case TESTED:
                  tested++;
                  break;
              case NOT_COVERED:
                  notCovered++;
                  break;
              case PSEUDO_TESTED:
                  psedudoTested++;
                  break;
              case PARTIALLY_TESTED:
                  partiallyTested++;
                  break;
              }
          }
          methodsStats.put(TESTED, tested);
          methodsStats.put(NOT_COVERED, notCovered);
          methodsStats.put(PSEUDO_TESTED, psedudoTested);
          methodsStats.put(PARTIALLY_TESTED, partiallyTested);
      } catch (IOException exc) {
          throw Unchecked.translateCheckedException(exc);
      }
  }

  private HashMap<String, List<MutationResult>> aggregateMethods(List<ClassMutationResults> results) {
      HashMap<String, List<MutationResult>> methodMap = new HashMap<String, List<MutationResult>>();

      for (ClassMutationResults classMutationResults : results) {
          for (MutationResult result : classMutationResults.getMutations()) {
              String key = getMethodKey(result);
              if (methodMap.containsKey(key)) {
                  methodMap.get(key).add(result);
              } else {
                  List<MutationResult> mutationResults = new LinkedList<MutationResult>();
                  mutationResults.add(result);
                  methodMap.put(key, mutationResults);
              }
          }
      }
      return methodMap;
  }

  private String getMethodKey(MutationResult result) {
      String className = result.getDetails().getClassName().asJavaName();
      String methodName = result.getDetails().getMethod().name();
      String methodDescription = result.getDetails().getId().getLocation().getMethodDesc();

      return className + "." + methodName + methodDescription;
  }

  private String classifyMethod(List<MutationResult> methodResults) throws IOException {
      List<String> detected = new LinkedList<String>();
      List<String> notDetected = new LinkedList<String>();
      List<String> notCovered = new LinkedList<String>();

      for (MutationResult result : methodResults) {
          DetectionStatus status = result.getStatus();
          String mutator = result.getDetails().getMutator();
          if (status.isDetected()) {
              detected.add(mutator);
          } else if (status.equals(DetectionStatus.NO_COVERAGE)) {
              notCovered.add(mutator);
          } else {
              notDetected.add(mutator);
          }
      }

      String classification = TESTED;

      if (notCovered.size() > 0) {
          assert detected.size() == 0; // This only makes sense in descartes but not in gregor.
          assert notDetected.size() == 0;

          classification = NOT_COVERED;
      } else {
          assert notDetected.size() > 0 || detected.size() > 0;

          if (notDetected.size() > 0) {
              classification = PSEUDO_TESTED;

              if (detected.size() > 0) {
                  classification = PARTIALLY_TESTED;
              }
          }
      }
      return classification;
  }
}
