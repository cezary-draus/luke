/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.luke.models.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.util.CharFilterFactory;
import org.apache.lucene.analysis.util.TokenFilterFactory;
import org.apache.lucene.analysis.util.TokenizerFactory;
import org.apache.lucene.luke.models.LukeException;
import org.apache.lucene.util.AttributeImpl;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

public class AnalysisImpl implements Analysis {

  private static final Logger logger = LoggerFactory.getLogger(AnalysisImpl.class);

  private List<Class<? extends Analyzer>> presetAnalyzerTypes;

  private Analyzer analyzer;

  Analyzer getAnalyzer() {
    return this.analyzer;
  }

  @Override
  public void addExternalJars(List<String> jarFiles) throws LukeException {
    List<URL> urls = new ArrayList<>();
    for (String jarFile : jarFiles) {
      Path path = FileSystems.getDefault().getPath(jarFile);
      if (!Files.exists(path) || !jarFile.endsWith(".jar")) {
        throw new LukeException(String.format("Invalid jar file path: %s", jarFile));
      }
      try {
        urls.add(path.toUri().toURL());
      } catch (MalformedURLException e) {
        throw new LukeException(e.getMessage(), e);
      }
    }

    URLClassLoader classLoader = new URLClassLoader(
        urls.toArray(new URL[urls.size()]), ClassLoader.getSystemClassLoader());
    CharFilterFactory.reloadCharFilters(classLoader);
    TokenizerFactory.reloadTokenizers(classLoader);
    TokenFilterFactory.reloadTokenFilters(classLoader);
  }

  @Override
  public Collection<Class<? extends Analyzer>> getPresetAnalyzerTypes() {
    if (presetAnalyzerTypes == null) {
      presetAnalyzerTypes = new ArrayList<>();
      for (Class<? extends Analyzer> clazz : getInstantiableSubTypesBuiltIn(Analyzer.class)) {
        try {
          // add to presets if no args constructor is available
          clazz.getConstructor();
          presetAnalyzerTypes.add(clazz);
        } catch (NoSuchMethodException e) {
        }
      }
    }
    return presetAnalyzerTypes;
  }

  @Override
  public Collection<Class<? extends CharFilterFactory>> getAvailableCharFilterFactories() {
    return CharFilterFactory.availableCharFilters().stream()
        .map(CharFilterFactory::lookupClass)
        .sorted(Comparator.comparing(Class::getName))
        .collect(Collectors.toList());
  }

  @Override
  public Collection<Class<? extends TokenizerFactory>> getAvailableTokenizerFactories() {
    return TokenizerFactory.availableTokenizers().stream()
        .map(TokenizerFactory::lookupClass)
        .sorted(Comparator.comparing(Class::getName))
        .collect(Collectors.toList());
  }

  @Override
  public Collection<Class<? extends TokenFilterFactory>> getAvailableTokenFilterFactories() {
    return TokenFilterFactory.availableTokenFilters().stream()
        .map(TokenFilterFactory::lookupClass)
        .sorted(Comparator.comparing(Class::getName))
        .collect(Collectors.toList());
  }

  private <T> List<Class<? extends T>> getInstantiableSubTypesBuiltIn(Class<T> superType) {
    Reflections reflections = new Reflections(new ConfigurationBuilder()
        .setUrls(ClasspathHelper.forPackage("org.apache.lucene"))
        .setScanners(new SubTypesScanner())
        .filterInputsBy(new FilterBuilder().include("org\\.apache\\.lucene\\.analysis.*")));
    return reflections.getSubTypesOf(superType).stream()
        .filter(type -> !Modifier.isAbstract(type.getModifiers()))
        .filter(type -> !type.getSimpleName().startsWith("Mock"))
        .sorted(Comparator.comparing(Class::getName))
        .collect(Collectors.toList());
  }

  @Override
  public List<Token> analyze(@Nonnull String text) throws LukeException {
    if (analyzer == null) {
      throw new LukeException("Analyzer is not set.");
    }

    List<Token> result = new ArrayList<>();
    try {
      TokenStream stream = analyzer.tokenStream("", text);
      stream.reset();

      CharTermAttribute charAtt = stream.getAttribute(CharTermAttribute.class);
      while (stream.incrementToken()) {
        Token token = new Token();
        token.term = charAtt.toString();
        Iterator<AttributeImpl> itr = stream.getAttributeImplsIterator();
        while (itr.hasNext()) {
          AttributeImpl att = itr.next();
          TokenAttribute tokenAtt = new TokenAttribute();
          tokenAtt.attClass = att.getClass().getSimpleName();
          tokenAtt.attValues = new LinkedHashMap<>();
          att.reflectWith((attClass, key, value) -> {
            if (value != null)
              tokenAtt.attValues.put(key, value.toString());
          });
          token.attributes.add(tokenAtt);
        }
        result.add(token);
      }
      stream.close();

      return result;
    } catch (IOException e) {
      throw new LukeException(e.getMessage(), e);
    }
  }

  @Override
  public Analyzer createAnalyzerFromClassName(@Nonnull String analyzerType) throws LukeException {
    try {
      Class<? extends Analyzer> clazz = Class.forName(analyzerType).asSubclass(Analyzer.class);
      this.analyzer = clazz.newInstance();
      return analyzer;
    } catch (ReflectiveOperationException e) {
      String errMsg = String.format("Failed to instantiate class: %s", analyzerType);
      logger.error(errMsg, e);
      throw new LukeException(errMsg, e);
    }
  }

  @Override
  public CustomAnalyzer buildCustomAnalyzer(@Nonnull CustomAnalyzerConfig config) throws LukeException {
    try {
      // create builder
      CustomAnalyzer.Builder builder = config.getConfigDir()
          .map(path -> CustomAnalyzer.builder(FileSystems.getDefault().getPath(path)))
          .orElse(CustomAnalyzer.builder());

      // set tokenizer
      builder.withTokenizer(Class.forName(config.getTokenizerConfig().getName()).asSubclass(TokenizerFactory.class),
          config.getTokenizerConfig().getParams());

      // add char filters
      for (CustomAnalyzerConfig.ComponentConfig cfConf : config.getCharFilterConfigs()) {
        builder.addCharFilter(Class.forName(cfConf.getName()).asSubclass(CharFilterFactory.class), cfConf.getParams());
      }

      // add token filters
      for (CustomAnalyzerConfig.ComponentConfig tfConf : config.getTokenFilterConfigs()) {
        builder.addTokenFilter(Class.forName(tfConf.getName()).asSubclass(TokenFilterFactory.class), tfConf.getParams());
      }

      // build analyzer
      this.analyzer = builder.build();
      return (CustomAnalyzer) analyzer;
    } catch (Exception e) {
      String errMsg = "Failed to build custom analyzer. " + e.getMessage();
      logger.error(errMsg, e);
      throw new LukeException(errMsg, e);
    }
  }

  @Override
  public Analyzer currentAnalyzer() throws LukeException {
    if (analyzer == null) {
      throw new LukeException("Analyzer is not set.");
    }
    return analyzer;
  }

}