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
package org.apache.sysml.api.dl
import scala.collection.JavaConversions._
import caffe.Caffe.LayerParameter;
import caffe.Caffe.NetParameter;
import org.apache.sysml.parser.LanguageException;
import com.google.protobuf.TextFormat;
import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.runtime.util.LocalFileUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import caffe.Caffe.SolverParameter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import org.apache.sysml.runtime.DMLRuntimeException

object Utils {
  
  def parseSolver(solverFilePath:String): CaffeSolver = parseSolver(readCaffeSolver(solverFilePath))
	def parseSolver(solver:SolverParameter): CaffeSolver = {
	  val momentum = if(solver.hasMomentum) solver.getMomentum else 0.0
	  val lambda = if(solver.hasWeightDecay) solver.getWeightDecay else 0.0
	  val delta = if(solver.hasDelta) solver.getDelta else 0.0
	  
	  solver.getType.toLowerCase match {
	    case "sgd" => new SGD(lambda, momentum)
	    case "adagrad" => new AdaGrad(lambda, delta)
	    case "nesterov" => new Nesterov(lambda, momentum)
	    case _ => throw new DMLRuntimeException("The solver type is not supported: " + solver.getType + ". Try: SGD, AdaGrad or Nesterov.")
	  }
    
  }
	
  def getPrefix():String = {
    val f = new File("nn")
    if(f.exists() && f.isDirectory()) {
      Barista.LOG.info("Since nn directory exists in current folder, using it.")
      return "nn"
    }
    else {
      // TODO: Extract from the jar
      throw new RuntimeException("In current version, we require that you download the nn folder into current directory from https://github.com/apache/incubator-systemml/tree/master/scripts/staging/SystemML-NN")
    }
  }
  
	// --------------------------------------------------------------
	// Caffe utility functions
	def readCaffeNet(netFilePath:String):NetParameter = {
		val reader:InputStreamReader = getInputStreamReader(netFilePath); 
  	val builder:NetParameter.Builder =  NetParameter.newBuilder();
  	TextFormat.merge(reader, builder);
  	return builder.build();
	}
	
	def readCaffeSolver(solverFilePath:String):SolverParameter = {
		val reader = getInputStreamReader(solverFilePath);
		val builder =  SolverParameter.newBuilder();
		TextFormat.merge(reader, builder);
		return builder.build();
	}
	
	// --------------------------------------------------------------
	// File IO utility functions
	def getInputStreamReader(filePath:String ):InputStreamReader = {
		//read solver script from file
		if(filePath == null)
			throw new LanguageException("file path was not specified!");
		if(filePath.startsWith("hdfs:")  || filePath.startsWith("gpfs:")) { 
			if( !LocalFileUtils.validateExternalFilename(filePath, true) )
				throw new LanguageException("Invalid (non-trustworthy) hdfs filename.");
			val fs = FileSystem.get(ConfigurationManager.getCachedJobConf());
			return new InputStreamReader(fs.open(new Path(filePath)));
		}
		else { 
			if( !LocalFileUtils.validateExternalFilename(filePath, false) )
				throw new LanguageException("Invalid (non-trustworthy) local filename.");
			return new InputStreamReader(new FileInputStream(new File(filePath)), "ASCII");
		}
	}
	// --------------------------------------------------------------
	
	val license = """
#-------------------------------------------------------------
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
# 
#   http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
#-------------------------------------------------------------
"""
}