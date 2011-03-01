/*
 * Copyright 2010 Henry Coles
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
package org.pitest.mutationtest.report;

import static org.pitest.mutationtest.report.DirectorySourceLocator.dir;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.antlr.stringtemplate.StringTemplate;
import org.antlr.stringtemplate.StringTemplateGroup;
import org.pitest.Description;
import org.pitest.TestResult;
import org.pitest.classinfo.ClassInfo;
import org.pitest.extension.TestListener;
import org.pitest.extension.TestUnit;
import org.pitest.functional.F;
import org.pitest.functional.FCollection;
import org.pitest.functional.Option;
import org.pitest.internal.IsolationUtils;
import org.pitest.mutationtest.MutationResultList;
import org.pitest.mutationtest.instrument.MutationMetaData;
import org.pitest.mutationtest.instrument.Statistics;
import org.pitest.mutationtest.instrument.UnRunnableMutationTestMetaData;
import org.pitest.util.FileUtil;

public class MutationHtmlReportListener implements TestListener {

  private final Collection<SourceLocator>     sourceRoots = new HashSet<SourceLocator>();
  private final File                          reportDir;
  private final List<MutationTestSummaryData> summaryData = new ArrayList<MutationTestSummaryData>();
  private final List<String>                  errors      = new ArrayList<String>();

  public MutationHtmlReportListener() {
    this("", dir("src/test/java"), dir("src/main/java"), dir("src"),
        dir("test"), dir("source"), dir("tst"), dir("java"));
  }

  public MutationHtmlReportListener(final String reportDir,
      final SourceLocator... locators) {
    final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddhhmm");
    final String timeString = sdf.format(new Date());
    this.reportDir = new File(addPathSeperatorIfMissing(reportDir) + timeString);
    this.reportDir.mkdirs();
    this.sourceRoots.addAll(Arrays.asList(locators));
  }

  private String addPathSeperatorIfMissing(final String s) {
    if (!s.endsWith(File.separator)) {
      return s + File.separator;
    } else {
      return s;
    }
  }

  private void extractMetaData(final TestResult tr) {
    final Option<MutationMetaData> d = tr.getValue(MutationMetaData.class);
    if (d.hasSome()) {
      processMetaData(d.value());
    } else {
      final Option<UnRunnableMutationTestMetaData> unrunnable = tr
          .getValue(UnRunnableMutationTestMetaData.class);
      if (unrunnable.hasSome()) {
        processUnruntest(unrunnable.value());
      }
    }
  }

  private void processUnruntest(final UnRunnableMutationTestMetaData unrunnable) {
    this.errors.add(unrunnable.getReason());
  }

  private void processMetaData(final MutationMetaData value) {

    System.out.println("Results for " + value.getMutatedClass());

    try {

      final Statistics stats = value.getStats().value();

      final String css = FileUtil.readToString(IsolationUtils
          .getContextClassLoader().getResourceAsStream(
              "templates/mutation/style.css"));
      final MutationTestSummaryData summaryData = value.getSummaryData();
      collectSummaryData(summaryData);

      final String fileName = summaryData.getFileName();

      final BufferedWriter bf = new BufferedWriter(new FileWriter(
          this.reportDir.getAbsolutePath() + File.separatorChar + fileName));

      final StringTemplateGroup group = new StringTemplateGroup("mutation_test");
      final StringTemplate st = group
          .getInstanceOf("templates/mutation/mutation_report");
      st.setAttribute("css", css);
      st.setAttribute("summary", summaryData);
      st.setAttribute("tests", getTests(value));
      st.setAttribute("mutators", value.getConfig().getMutatorNames());

      final Collection<SourceFile> sourceFiles = createAnnotatedSoureFiles(
          value, stats);

      st.setAttribute("sourceFiles", sourceFiles);
      st.setAttribute("mutatedClasses", value.getMutatedClass());

      // st.setAttribute("groups", groups);
      bf.write(st.toString());
      bf.close();

    } catch (final IOException ex) {
      ex.printStackTrace();
    }
  }

  private Collection<SourceFile> createAnnotatedSoureFiles(
      final MutationMetaData value, final Statistics stats) throws IOException {
    final Collection<SourceFile> sourceFiles = new ArrayList<SourceFile>();
    for (final String each : value.getSourceFiles()) {
      final MutationResultList mutationsForThisFile = value
          .getResultsForSourceFile(each);
      final List<Line> lines = createAnnotatedSourceCodeLines(each,
          mutationsForThisFile, stats, value.getClassesForSourceFile(each));

      sourceFiles.add(new SourceFile(each, lines, mutationsForThisFile
          .groupMutationsByLine()));
    }
    return sourceFiles;
  }

  private void collectSummaryData(final MutationTestSummaryData summaryData) {
    synchronized (this.summaryData) {
      this.summaryData.add(summaryData);
    }

  }

  private List<Line> createAnnotatedSourceCodeLines(final String sourceFile,
      final MutationResultList mutationsForThisFile,
      final Statistics statistics, final Collection<ClassInfo> classes)
      throws IOException {
    final Option<Reader> reader = findSourceFile(classInfoToNames(classes),
        sourceFile);
    if (reader.hasSome()) {
      final AnnotatedLineFactory alf = new AnnotatedLineFactory(
          mutationsForThisFile, statistics, classes);
      return alf.convert(reader.value());
    }
    return Collections.emptyList();
  }

  private Collection<String> classInfoToNames(
      final Collection<ClassInfo> classes) {
    return FCollection.map(classes, classInfoToName());
  }

  private F<ClassInfo, String> classInfoToName() {
    return new F<ClassInfo, String>() {

      public String apply(final ClassInfo a) {
        return a.getName();
      }

    };
  }

  private Collection<TestUnit> getTests(final MutationMetaData value) {

    if (value.getStats().hasSome()) {
      return value.getStats().value().getAllTests();
    } else {
      return Collections.emptyList();
    }
  }

  private Option<Reader> findSourceFile(final Collection<String> classes,
      final String fileName) {
    for (final SourceLocator each : this.sourceRoots) {
      final Option<Reader> maybe = each.locate(classes, fileName);
      if (maybe.hasSome()) {
        return maybe;
      }
    }
    return Option.none();
  }

  public void onTestError(final TestResult tr) {
    extractMetaData(tr);
  }

  public void onTestFailure(final TestResult tr) {
    extractMetaData(tr);
  }

  public void onTestSkipped(final TestResult tr) {
    extractMetaData(tr);
  }

  public void onTestStart(final Description d) {

  }

  public void onTestSuccess(final TestResult tr) {
    extractMetaData(tr);

  }

  public void onRunEnd() {
    try {
      final StringTemplateGroup group = new StringTemplateGroup("mutation_test");
      final StringTemplate st = group
          .getInstanceOf("templates/mutation/mutation_index");
      final BufferedWriter bw = new BufferedWriter(new FileWriter(
          this.reportDir.getAbsolutePath() + File.separatorChar + "index.html"));
      st.setAttribute("summaryList", this.summaryData);
      st.setAttribute("errors", this.errors);
      bw.write(st.toString());
      bw.close();

    } catch (final IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

  }

  public void onRunStart() {

  }

}