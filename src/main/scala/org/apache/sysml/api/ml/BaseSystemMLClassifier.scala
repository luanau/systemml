/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.api.ml

import org.apache.commons.logging.LogFactory;
import org.apache.spark.api.java.JavaSparkContext
import org.apache.spark.rdd.RDD

import java.io.File

import org.apache.spark.SparkContext
import org.apache.spark.ml.{ Estimator, Model }
import org.apache.spark.sql.types.StructType
import org.apache.spark.ml.param.{ DoubleParam, Param, ParamMap, Params }
import org.apache.sysml.runtime.matrix.MatrixCharacteristics
import org.apache.sysml.runtime.matrix.data.MatrixBlock
import org.apache.sysml.runtime.DMLRuntimeException
import org.apache.sysml.runtime.instructions.spark.utils.{ RDDConverterUtils, RDDConverterUtilsExt }
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.api.mlcontext._
import org.apache.sysml.api.mlcontext.ScriptFactory._
import org.apache.spark.sql._
import org.apache.sysml.api.mlcontext.MLContext.ExplainLevel
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.conf.{ConfigurationManager, DMLConfig}

import java.util.HashMap

import scala.collection.JavaConversions._

import java.util.Random

/****************************************************
DESIGN DOCUMENT for MLLEARN API:
The mllearn API supports LogisticRegression, LinearRegression, SVM, NaiveBayes
and Caffe2DML. Every algorithm in this API has a python wrapper (implemented in the mllearn python package)
and a Scala class where the actual logic is implementation.
Both wrapper and scala class follow the below hierarchy to reuse code and simplify the implementation.


                  BaseSystemMLEstimator
                          |
      --------------------------------------------
      |                                          |
BaseSystemMLClassifier                  BaseSystemMLRegressor
      ^                                          ^
      |                                          |
SVM, Caffe2DML, ...                          LinearRegression


To conform with MLLib API, for every algorithm, we support two classes for every algorithm:
1. Estimator for training: For example: SVM extends Estimator[SVMModel].
2. Model for prediction: For example: SVMModel extends Model[SVMModel]

Both BaseSystemMLRegressor and BaseSystemMLClassifier implements following methods for training:
1. For compatibility with scikit-learn: baseFit(X_mb: MatrixBlock, y_mb: MatrixBlock, sc: SparkContext): MLResults
2. For compatibility with MLLib: baseFit(df: ScriptsUtils.SparkDataType, sc: SparkContext): MLResults

In the above methods, we execute the DML script for the given algorithm using MLContext.
The missing piece of the puzzle is how does BaseSystemMLRegressor and BaseSystemMLClassifier interfaces
get the DML script. To enable this, each wrapper class has to implement following methods:
1. getTrainingScript(isSingleNode:Boolean):(Script object of mlcontext, variable name of X in the script:String, variable name of y in the script:String)
2. getPredictionScript(isSingleNode:Boolean): (Script object of mlcontext, variable name of X in the script:String)

****************************************************/
trait HasLaplace extends Params {
  final val laplace: Param[Double] = new Param[Double](this, "laplace", "Laplace smoothing specified by the user to avoid creation of 0 probabilities.")
  setDefault(laplace, 1.0)
  final def getLaplace: Double = $(laplace)
}
trait HasIcpt extends Params {
  final val icpt: Param[Int] = new Param[Int](this, "icpt", "Intercept presence, shifting and rescaling X columns")
  setDefault(icpt, 0)
  final def getIcpt: Int = $(icpt)
}
trait HasMaxOuterIter extends Params {
  final val maxOuterIter: Param[Int] = new Param[Int](this, "maxOuterIter", "max. number of outer (Newton) iterations")
  setDefault(maxOuterIter, 100)
  final def getMaxOuterIte: Int = $(maxOuterIter)
}
trait HasMaxInnerIter extends Params {
  final val maxInnerIter: Param[Int] = new Param[Int](this, "maxInnerIter", "max. number of inner (conjugate gradient) iterations, 0 = no max")
  setDefault(maxInnerIter, 0)
  final def getMaxInnerIter: Int = $(maxInnerIter)
}
trait HasTol extends Params {
  final val tol: DoubleParam = new DoubleParam(this, "tol", "the convergence tolerance for iterative algorithms")
  setDefault(tol, 0.000001)
  final def getTol: Double = $(tol)
}
trait HasRegParam extends Params {
  final val regParam: DoubleParam = new DoubleParam(this, "regParam", "regularization parameter")
  setDefault(regParam, 0.000001)
  final def getRegParam: Double = $(regParam)
}

trait BaseSystemMLEstimatorOrModel {
  def dmlRead(X:String, fileX:String):String = {
    val format = if(fileX.endsWith(".csv")) ", format=\"csv\"" else ""
	return X + " = read(\"" + fileX + "\"" + format + "); "
  }
  def dmlWrite(X:String):String = "write("+ X + ", \"output.mtx\", format=\"binary\"); "
  var enableGPU: Boolean                                                                          = false
  var forceGPU: Boolean                                                                           = false
  var explain: Boolean                                                                            = false
  var explainLevel: String                                                                        = "runtime"
  var statistics: Boolean                                                                         = false
  var statisticsMaxHeavyHitters: Int                                                              = 10
  val config: HashMap[String, String]                                                             = new HashMap[String, String]()
  def setGPU(enableGPU1: Boolean): BaseSystemMLEstimatorOrModel                                   = { enableGPU = enableGPU1; this }
  def setForceGPU(enableGPU1: Boolean): BaseSystemMLEstimatorOrModel                              = { forceGPU = enableGPU1; this }
  def setExplain(explain1: Boolean): BaseSystemMLEstimatorOrModel                                 = { explain = explain1; this }
  def setExplainLevel(explainLevel1: String): BaseSystemMLEstimatorOrModel                        = { explainLevel = explainLevel1; this }
  def setStatistics(statistics1: Boolean): BaseSystemMLEstimatorOrModel                           = { statistics = statistics1; this }
  def setStatisticsMaxHeavyHitters(statisticsMaxHeavyHitters1: Int): BaseSystemMLEstimatorOrModel = { statisticsMaxHeavyHitters = statisticsMaxHeavyHitters1; this }
  def setConfigProperty(key: String, value: String): BaseSystemMLEstimatorOrModel                 = { config.put(key, value); this }
  var localDmlConfig:DMLConfig = null
  def getConfigProperty(key: String): String = {
    if(config.containsKey())
      return config.get(key)
    if(localDmlConfig == null) {
      localDmlConfig = ConfigurationManager.getDMLConfig()
    }
    return localDmlConfig.getTextValue(key)
  }
  
  def updateML(ml: MLContext): Unit = {
	  System.gc();
	  ml.setGPU(enableGPU); ml.setForceGPU(forceGPU);
    ml.setExplain(explain); ml.setExplainLevel(explainLevel);
    ml.setStatistics(statistics); ml.setStatisticsMaxHeavyHitters(statisticsMaxHeavyHitters);
    config.map(x => ml.setConfigProperty(x._1, x._2))
    // Since this is an approximate information, the check below only warns the users of unintended side effects
    // (for example: holding too many strong references) and is not added as a safeguard.
    val freeMem = Runtime.getRuntime().freeMemory();
    if(freeMem < OptimizerUtils.getLocalMemBudget()) {
    	val LOG = LogFactory.getLog(classOf[BaseSystemMLEstimatorOrModel].getName())
    	LOG.warn("SystemML local memory budget:" + OptimizerUtils.toMB(OptimizerUtils.getLocalMemBudget()) + " mb. Approximate free memory available on the driver JVM:" + OptimizerUtils.toMB(freeMem) + " mb.");
    }
  }
  def copyProperties(other: BaseSystemMLEstimatorOrModel): BaseSystemMLEstimatorOrModel = {
    other.setGPU(enableGPU); other.setForceGPU(forceGPU);
    other.setExplain(explain); other.setExplainLevel(explainLevel);
    other.setStatistics(statistics); other.setStatisticsMaxHeavyHitters(statisticsMaxHeavyHitters);
    config.map(x => other.setConfigProperty(x._1, x._2))
    return other
  }
}

trait BaseSystemMLEstimator extends BaseSystemMLEstimatorOrModel {
  def transformSchema(schema: StructType): StructType = schema
  var mloutput: MLResults                             = null
  // Returns the script and variables for X and y
  def getTrainingScript(isSingleNode: Boolean): (Script, String, String)

  def toDouble(i: Int): java.lang.Double =
    double2Double(i.toDouble)

  def toDouble(d: Double): java.lang.Double =
    double2Double(d)

}

trait BaseSystemMLEstimatorModel extends BaseSystemMLEstimatorOrModel {
  def toDouble(i: Int): java.lang.Double =
    double2Double(i.toDouble)
  def toDouble(d: Double): java.lang.Double =
    double2Double(d)

  def transform_probability(X: MatrixBlock): MatrixBlock;

  def transformSchema(schema: StructType): StructType = schema

  // Returns the script and variable for X
  def getPredictionScript(isSingleNode: Boolean): (Script, String)
  def baseEstimator(): BaseSystemMLEstimator
  def modelVariables(): List[String]
  // self.model.load(self.sc._jsc, weights, format, sep)
  def load(sc: JavaSparkContext, outputDir: String, sep: String, eager: Boolean = false): Unit = {
    val dmlScript = new StringBuilder
    dmlScript.append("print(\"Loading the model from " + outputDir + "...\")\n")
    val tmpSum = "tmp_sum_var" + Math.abs((new Random()).nextInt())
    if (eager)
      dmlScript.append(tmpSum + " = 0\n")
    for (varName <- modelVariables) {
      dmlScript.append(varName + " = read(\"" + outputDir + sep + varName + ".mtx\")\n")
      if (eager)
        dmlScript.append(tmpSum + " = " + tmpSum + " + 0.001*mean(" + varName + ")\n")
    }
    if (eager) {
      dmlScript.append("if(" + tmpSum + " > 0) { print(\"Loaded the model\"); } else {  print(\"Loaded the model.\"); }")
    }
    val script = dml(dmlScript.toString)
    for (varName <- modelVariables) {
      script.out(varName)
    }
    val ml = new MLContext(sc)
    baseEstimator.mloutput = ml.execute(script)
  }
  def save(sc: JavaSparkContext, outputDir: String, format: String = "binary", sep: String = "/"): Unit = {
    if (baseEstimator.mloutput == null) throw new DMLRuntimeException("Cannot save as you need to train the model first using fit")
    val dmlScript = new StringBuilder
    dmlScript.append("print(\"Saving the model to " + outputDir + "...\")\n")
    for (varName <- modelVariables) {
      dmlScript.append("write(" + varName + ", \"" + outputDir + sep + varName + ".mtx\", format=\"" + format + "\")\n")
    }
    val script = dml(dmlScript.toString)
    for (varName <- modelVariables) {
      script.in(varName, baseEstimator.mloutput.getMatrix(varName))
    }
    val ml = new MLContext(sc)
    ml.execute(script)
  }
}

trait BaseSystemMLClassifier extends BaseSystemMLEstimator {
  def baseFit(X_file: String, y_file: String, sc: SparkContext): MLResults = {
	val isSingleNode = false
	val ml           = new MLContext(sc)
	updateML(ml)
	val readScript      = dml(dmlRead("X", X_file) + dmlRead("y", y_file)).out("X", "y")
	val res = ml.execute(readScript)
	val ret             = getTrainingScript(isSingleNode)
	val script          = ret._1.in(ret._2, res.getMatrix("X")).in(ret._3, res.getMatrix("y"))
	ml.execute(script)
  }
  def baseFit(X_mb: MatrixBlock, y_mb: MatrixBlock, sc: SparkContext): MLResults = {
    val isSingleNode = true
    val ml           = new MLContext(sc)
    updateML(ml)
    y_mb.recomputeNonZeros();
    val ret    = getTrainingScript(isSingleNode)
    val script = ret._1.in(ret._2, X_mb).in(ret._3, y_mb)
    ml.execute(script)
  }
  def baseFit(df: ScriptsUtils.SparkDataType, sc: SparkContext): MLResults = {
    val isSingleNode = false
    val ml           = new MLContext(df.rdd.sparkContext)
    updateML(ml)
    val mcXin           = new MatrixCharacteristics()
    val Xin             = RDDConverterUtils.dataFrameToBinaryBlock(sc, df.asInstanceOf[DataFrame].select("features"), mcXin, false, true)
    val revLabelMapping = new java.util.HashMap[Int, String]
    val yin             = df.select("label")
    val ret             = getTrainingScript(isSingleNode)
    val mmXin           = new MatrixMetadata(mcXin)
    val Xbin            = new Matrix(Xin, mmXin)
    val script          = ret._1.in(ret._2, Xbin).in(ret._3, yin)
    ml.execute(script)
  }
}

trait BaseSystemMLClassifierModel extends BaseSystemMLEstimatorModel {  
  // Helper method that executes the prediction script:
  def executePredictionScript(sc: SparkContext, C: Int, H: Int, W: Int, isSingleNode:Boolean, 
      outputProbability:Boolean, probVar:String, 
      addInputOutput: (Script, String) => Script): Matrix = {
    val ml = new MLContext(sc)
    updateML(ml)
    // getPredictionScript sets the hyperparameter as well as the output parameter 
    val script = getPredictionScript(isSingleNode)
    if(!outputProbability) {
      // Append prediction code:
      val newDML = if(H == 1 && W == 1) {
        "source(\"nn/util.dml\") as util;\n" +
        script._1.getScriptString + 
        "\nPrediction = util::predict_class(" + probVar + ", " + C + ", " + H + ", " + W + ");\n"
      } else {
        "\nPrediction = rowIndexMax(" + probVar + ");\n" // predictions are 1-based
      }
      script._1.setScriptString(newDML)
      // Modify the output variables -> remove probability matrix and add Prediction
      val outputVariables = new java.util.HashSet[String](script._1.getOutputVariables)
      // Register probVar as output as well to avoid writing of empty meta-data files in scripts like Naive Bayes
      // outputVariables.remove(probVar)
      outputVariables.add("Prediction")
      script._1.clearOutputs()
      script._1.out(outputVariables.toList)
    }
    val modelPredict = ml.execute(addInputOutput(script._1, script._2))
    return modelPredict.getMatrix(if(outputProbability) probVar else "Prediction")
  }
  // --------------------------------------------------------------------------------------------------------------
  // Methods where the input and output probability/predictions are MatrixBlock.
  def baseTransformHelper(X: MatrixBlock, sc: SparkContext, probVar: String, C: Int, H: Int, W: Int, outputProbability:Boolean): MatrixBlock = {
    val addInputOutput = (script:Script, xVar: String) => {
      script.in(xVar, X, new MatrixMetadata(X.getNumRows, X.getNumColumns, X.getNonZeros))
    }
    return executePredictionScript(sc, C, H, W, true, outputProbability, probVar, addInputOutput).toMatrixBlock
  }
  // Methods that return probabilities:
  def baseTransformProbability(X: MatrixBlock, sc: SparkContext, probVar: String): MatrixBlock =
    baseTransformProbability(X, sc, probVar, -1, 1, 1)
  def baseTransformProbability(X: MatrixBlock, sc: SparkContext, probVar: String, C: Int, H: Int, W: Int): MatrixBlock = 
    baseTransformHelper(X, sc, probVar, C, H, W, true)
  // Methods that return predictions:
  def baseTransform(X: MatrixBlock, sc: SparkContext, probVar: String): MatrixBlock = 
    baseTransform(X, sc, probVar, -1, 1, 1)
  def baseTransform(X: MatrixBlock, sc: SparkContext, probVar: String, C: Int, H: Int, W: Int): MatrixBlock = 
    baseTransformHelper(X, sc, probVar, C, H, W, false)
  // --------------------------------------------------------------------------------------------------------------
  // Methods where the input is a file path and output probability/predictions are returned as a file path.
  def baseTransformHelper(X: String, sc: SparkContext, probVar: String, C: Int, H: Int, W: Int, outputProbability:Boolean): String = {
    val ml = new MLContext(sc)
    updateML(ml)
    val addInputOutput = (script:Script, xVar: String) => {
      // Execution 1: Read X from the file system using MLContext
      val readScript = dml(dmlRead("X", X)).out("X")
      script.in(xVar, ml.execute(dml(dmlRead("X", X)).out("X")).getMatrix("X"))
    }
    // Execution 2: Execute the prediction script
    val Prob =  executePredictionScript(sc, C, H, W, true, outputProbability, probVar, addInputOutput)
    // Execution 3: Execute the write script to dump the matrix Prob  
    ml.execute(dml(dmlWrite("Prob")).in("Prob", Prob))
    "output.mtx"
  }
  // Methods that return probabilities:
  def baseTransformProbability(X: String, sc: SparkContext, probVar: String): String =
    baseTransformProbability(X, sc, probVar, -1, 1, 1)
  def baseTransformProbability(X: String, sc: SparkContext, probVar: String, C: Int, H: Int, W: Int): String = 
    baseTransformHelper(X, sc, probVar, C, H, W, true)
  // Methods that return predictions:
  def baseTransform(X_file: String, sc: SparkContext, probVar: String): String = 
    baseTransform(X_file, sc, probVar, -1, 1, 1)
  def baseTransform(X: String, sc: SparkContext, probVar: String, C: Int, H: Int, W: Int): String = 
    baseTransformHelper(X, sc, probVar, C, H, W, false)
  // --------------------------------------------------------------------------------------------------------------
  // Methods where the input and output probability/predictions are DataFrame.
  def baseTransform(df: ScriptsUtils.SparkDataType, sc: SparkContext, probVar: String, outputProb: Boolean = true): DataFrame =
    baseTransform(df, sc, probVar, outputProb, -1, 1, 1)
  def baseTransformHelper(df: ScriptsUtils.SparkDataType, sc: SparkContext, probVar: String, outputProb: Boolean, C: Int, H: Int, W: Int): Matrix = {
    val isSingleNode = false
    val ml           = new MLContext(sc)
    updateML(ml)
    val mcXin        = new MatrixCharacteristics()
    val Xin          = RDDConverterUtils.dataFrameToBinaryBlock(df.rdd.sparkContext, df.asInstanceOf[DataFrame].select("features"), mcXin, false, true)
    val script       = getPredictionScript(isSingleNode)
    val mmXin        = new MatrixMetadata(mcXin)
    val Xin_bin      = new Matrix(Xin, mmXin)
    val modelPredict = ml.execute(script._1.in(script._2, Xin_bin))
    return modelPredict.getMatrix(probVar)
  }
  def baseTransform(df: ScriptsUtils.SparkDataType, sc: SparkContext, probVar: String, outputProb: Boolean, C: Int, H: Int, W: Int): DataFrame = {
    val Prob = baseTransformHelper(df, sc, probVar, outputProb, C, H, W)
    val script1 = dml("source(\"nn/util.dml\") as util; Prediction = util::predict_class(Prob, C, H, W);")
      .out("Prediction")
      .in("Prob", Prob)
      .in("C", C)
      .in("H", H)
      .in("W", W)
    val predLabelOut = (new MLContext(sc)).execute(script1)
    val predictedDF  = predLabelOut.getDataFrame("Prediction").select(RDDConverterUtils.DF_ID_COLUMN, "C1").withColumnRenamed("C1", "prediction")
    if (outputProb) {
      val prob    = Prob.toDFVectorWithIDColumn().withColumnRenamed("C1", "probability").select(RDDConverterUtils.DF_ID_COLUMN, "probability")
      val dataset = RDDConverterUtilsExt.addIDToDataFrame(df.asInstanceOf[DataFrame], df.sparkSession, RDDConverterUtils.DF_ID_COLUMN)
      return PredictionUtils.joinUsingID(dataset, PredictionUtils.joinUsingID(prob, predictedDF))
    } else {
      val dataset = RDDConverterUtilsExt.addIDToDataFrame(df.asInstanceOf[DataFrame], df.sparkSession, RDDConverterUtils.DF_ID_COLUMN)
      return PredictionUtils.joinUsingID(dataset, predictedDF)
    }
  }
}
