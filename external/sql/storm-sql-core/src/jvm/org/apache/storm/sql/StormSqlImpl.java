/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.sql;

import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.impl.AggregateFunctionImpl;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.storm.StormSubmitter;
import org.apache.storm.generated.SubmitOptions;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.storm.sql.compiler.backends.standalone.PlanCompiler;
import org.apache.storm.sql.javac.CompilingClassLoader;
import org.apache.storm.sql.parser.ColumnConstraint;
import org.apache.storm.sql.parser.ColumnDefinition;
import org.apache.storm.sql.parser.SqlCreateFunction;
import org.apache.storm.sql.parser.SqlCreateTable;
import org.apache.storm.sql.parser.StormParser;
import org.apache.storm.sql.runtime.*;
import org.apache.storm.sql.runtime.trident.AbstractTridentProcessor;
import org.apache.storm.trident.TridentTopology;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.apache.storm.sql.compiler.CompilerUtil.TableBuilderInfo;

class StormSqlImpl extends StormSql {
  private final JavaTypeFactory typeFactory = new JavaTypeFactoryImpl(
      RelDataTypeSystem.DEFAULT);
  private final SchemaPlus schema = Frameworks.createRootSchema(true);
  private boolean hasUdf = false;

  @Override
  public void execute(
      Iterable<String> statements, ChannelHandler result)
      throws Exception {
    Map<String, DataSource> dataSources = new HashMap<>();
    for (String sql : statements) {
      StormParser parser = new StormParser(sql);
      SqlNode node = parser.impl().parseSqlStmtEof();
      if (node instanceof SqlCreateTable) {
        handleCreateTable((SqlCreateTable) node, dataSources);
      } else if (node instanceof SqlCreateFunction) {
        handleCreateFunction((SqlCreateFunction) node);
      } else {
        FrameworkConfig config = buildFrameWorkConfig();
        Planner planner = Frameworks.getPlanner(config);
        SqlNode parse = planner.parse(sql);
        SqlNode validate = planner.validate(parse);
        RelNode tree = planner.convert(validate);
        PlanCompiler compiler = new PlanCompiler(typeFactory);
        AbstractValuesProcessor proc = compiler.compile(tree);
        proc.initialize(dataSources, result);
      }
    }
  }

  @Override
  public void submit(
      String name, Iterable<String> statements, Map<String, ?> stormConf, SubmitOptions opts,
      StormSubmitter.ProgressListener progressListener, String asUser)
      throws Exception {
    Map<String, ISqlTridentDataSource> dataSources = new HashMap<>();
    for (String sql : statements) {
      StormParser parser = new StormParser(sql);
      SqlNode node = parser.impl().parseSqlStmtEof();
      if (node instanceof SqlCreateTable) {
        handleCreateTableForTrident((SqlCreateTable) node, dataSources);
      } else if (node instanceof SqlCreateFunction) {
        handleCreateFunction((SqlCreateFunction) node);
      }  else {
        FrameworkConfig config = buildFrameWorkConfig();
        Planner planner = Frameworks.getPlanner(config);
        SqlNode parse = planner.parse(sql);
        SqlNode validate = planner.validate(parse);
        RelNode tree = planner.convert(validate);
        org.apache.storm.sql.compiler.backends.trident.PlanCompiler compiler =
                new org.apache.storm.sql.compiler.backends.trident.PlanCompiler(dataSources, typeFactory);
        TridentTopology topo = compiler.compile(tree);
        Path jarPath = null;
        try {
          // PlanCompiler configures the topology without any new classes, so we don't need to add anything into topology jar
          // packaging empty jar since topology jar is needed for topology submission
          // Topology will be serialized and sent to Nimbus, and deserialized and executed in workers.

          jarPath = Files.createTempFile("storm-sql", ".jar");
          System.setProperty("storm.jar", jarPath.toString());
          packageEmptyTopology(jarPath);
          StormSubmitter.submitTopologyAs(name, stormConf, topo.build(), opts, progressListener, asUser);
        } finally {
          if (jarPath != null) {
            Files.delete(jarPath);
          }
        }
      }
    }
  }

  @Override
  public void explain(Iterable<String> statements) throws Exception {
    Map<String, ISqlTridentDataSource> dataSources = new HashMap<>();
    for (String sql : statements) {
      StormParser parser = new StormParser(sql);
      SqlNode node = parser.impl().parseSqlStmtEof();

      System.out.println("===========================================================");
      System.out.println("query>");
      System.out.println(sql);
      System.out.println("-----------------------------------------------------------");

      if (node instanceof SqlCreateTable) {
        handleCreateTableForTrident((SqlCreateTable) node, dataSources);
        System.out.println("No plan presented on DDL");
      } else if (node instanceof SqlCreateFunction) {
        handleCreateFunction((SqlCreateFunction) node);
        System.out.println("No plan presented on DDL");
      } else {
        FrameworkConfig config = buildFrameWorkConfig();
        Planner planner = Frameworks.getPlanner(config);
        SqlNode parse = planner.parse(sql);
        SqlNode validate = planner.validate(parse);
        RelNode tree = planner.convert(validate);

        // TODO: change to all attributes when we change to cost-based planner
        String plan = RelOptUtil.toString(tree, SqlExplainLevel.NON_COST_ATTRIBUTES);
        System.out.println("plan>");
        System.out.println(plan);
      }

      System.out.println("===========================================================");
    }
  }

  private void packageEmptyTopology(Path jar) throws IOException {
    Manifest manifest = new Manifest();
    Attributes attr = manifest.getMainAttributes();
    attr.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    try (JarOutputStream out = new JarOutputStream(
        new BufferedOutputStream(new FileOutputStream(jar.toFile())), manifest)) {
    }
  }

  private void handleCreateTable(
      SqlCreateTable n, Map<String, DataSource> dataSources) {
    List<FieldInfo> fields = updateSchema(n);
    DataSource ds = DataSourcesRegistry.construct(n.location(), n
        .inputFormatClass(), n.outputFormatClass(), fields);
    if (ds == null) {
      throw new RuntimeException("Cannot construct data source for " + n
          .tableName());
    } else if (dataSources.containsKey(n.tableName())) {
      throw new RuntimeException("Duplicated definition for table " + n
          .tableName());
    }
    dataSources.put(n.tableName(), ds);
  }

  private void handleCreateFunction(SqlCreateFunction sqlCreateFunction) throws ClassNotFoundException {
    if(sqlCreateFunction.jarName() != null) {
      throw new UnsupportedOperationException("UDF 'USING JAR' not implemented");
    }
    Method method;
    Function function;
    if ((method=findMethod(sqlCreateFunction.className(), "evaluate")) != null) {
      function = ScalarFunctionImpl.create(method);
    } else if (findMethod(sqlCreateFunction.className(), "add") != null) {
      function = AggregateFunctionImpl.create(Class.forName(sqlCreateFunction.className()));
    } else {
      throw new RuntimeException("Invalid scalar or aggregate function");
    }
    schema.add(sqlCreateFunction.functionName().toUpperCase(), function);
    hasUdf = true;
  }

  private Method findMethod(String clazzName, String methodName) throws ClassNotFoundException {
    Class<?> clazz = Class.forName(clazzName);
    for (Method method : clazz.getMethods()) {
      if (method.getName().equals(methodName)) {
        return method;
      }
    }
    return null;
  }

  private void handleCreateTableForTrident(
      SqlCreateTable n, Map<String, ISqlTridentDataSource> dataSources) {
    List<FieldInfo> fields = updateSchema(n);
    ISqlTridentDataSource ds = DataSourcesRegistry.constructTridentDataSource(n.location(), n
        .inputFormatClass(), n.outputFormatClass(), n.properties(), fields);
    if (ds == null) {
      throw new RuntimeException("Failed to find data source for " + n
          .tableName() + " URI: " + n.location());
    } else if (dataSources.containsKey(n.tableName())) {
      throw new RuntimeException("Duplicated definition for table " + n
          .tableName());
    }
    dataSources.put(n.tableName(), ds);
  }

  private List<FieldInfo> updateSchema(SqlCreateTable n) {
    TableBuilderInfo builder = new TableBuilderInfo(typeFactory);
    List<FieldInfo> fields = new ArrayList<>();
    for (ColumnDefinition col : n.fieldList()) {
      builder.field(col.name(), col.type(), col.constraint());
      RelDataType dataType = col.type().deriveType(typeFactory);
      Class<?> javaType = (Class<?>)typeFactory.getJavaClass(dataType);
      ColumnConstraint constraint = col.constraint();
      boolean isPrimary = constraint != null && constraint instanceof ColumnConstraint.PrimaryKey;
      fields.add(new FieldInfo(col.name(), javaType, isPrimary));
    }

    Table table = builder.build();
    schema.add(n.tableName(), table);
    return fields;
  }

  private FrameworkConfig buildFrameWorkConfig() {
    if (hasUdf) {
      List<SqlOperatorTable> sqlOperatorTables = new ArrayList<>();
      sqlOperatorTables.add(SqlStdOperatorTable.instance());
      sqlOperatorTables.add(new CalciteCatalogReader(CalciteSchema.from(schema),
                                                     false,
                                                     Collections.<String>emptyList(), typeFactory));
      return Frameworks.newConfigBuilder().defaultSchema(schema)
              .operatorTable(new ChainedSqlOperatorTable(sqlOperatorTables)).build();
    } else {
      return Frameworks.newConfigBuilder().defaultSchema(schema).build();
    }
  }
}
