package org.apache.sysml.api.dl

import caffe.Caffe.SolverParameter
import org.apache.sysml.runtime.DMLRuntimeException
import caffe.Caffe

trait CaffeSolver {
  def update(dmlScript:StringBuilder, layer:CaffeLayer):Unit;
  def init(dmlScript:StringBuilder, layer:CaffeLayer):Unit;
  def source(dmlScript:StringBuilder):Unit;
  def commaSep(arr:String*):String = {
	  if(arr.length == 1) arr(0) else {
	    var ret = arr(0)
	    for(i <- 1 until arr.length) {
	      ret = ret + "," + arr(i)
	    }
	    ret
	  }
	}
  
  def l2reg_update(lambda:Double, dmlScript:StringBuilder, layer:CaffeLayer):Unit = {
    // val donotRegularizeLayers:Boolean = layer.isInstanceOf[BatchNorm] || layer.isInstanceOf[Scale];
    if(lambda != 0 && layer.shouldUpdateWeight) {
      dmlScript.append("\t").append(layer.dW + "_reg = l2_reg::backward(" + layer.weight + ", " + lambda + ")\n")
      dmlScript.append("\t").append(layer.dW + " = " + layer.dW + " + " + layer.dW + "_reg\n")
    }
  }
}

class LearningRatePolicy(lr_policy:String="exp", base_lr:Double=0.01) {
  def this(solver:Caffe.SolverParameter) {
    this(solver.getLrPolicy, solver.getBaseLr)
    if(solver.hasGamma) setGamma(solver.getGamma)
    if(solver.hasStepsize) setStepsize(solver.getStepsize)
    if(solver.hasPower()) setPower(solver.getPower)
  }
  var gamma:Double = 0.95
  var step:Double = 100000
  var power:Double = 0.75
  def setGamma(gamma1:Double):Unit = { gamma = gamma1 } 
  def setStepsize(step1:Double):Unit = { step = step1 } 
  def setPower(power1:Double): Unit = { power = power1 }
  def updateLearningRate(dmlScript:StringBuilder):Unit = {
    val new_lr = lr_policy.toLowerCase match {
      case "fixed" => base_lr.toString
      case "step" => "(" + base_lr + " * " +  gamma + " ^ " + " floor(epoch/" + step + "))"
      case "exp" => "(" + base_lr + " * " + gamma + "^epoch)"
      case "inv" =>  "(" + base_lr + "* (1 + " + gamma + " * epoch) ^ (-" + power + "))"
      case "poly" => "(" + base_lr  + " * (1 - epoch/ max_epoch) ^ " + power + ")"
      case "sigmoid" => "(" + base_lr + "( 1/(1 + exp(-" + gamma + "* (epoch - " + step + "))))"
      case _ => throw new DMLRuntimeException("The lr policy is not supported:" + lr_policy)
    }
    dmlScript.append("lr = " + new_lr + "\n")
  }
}

/**
 * lambda: regularization parameter
 * momentum: Momentum value. Typical values are in the range of [0.5, 0.99], usually started at the lower end and annealed towards the higher end.
 */
class SGD(lambda:Double=5e-04, momentum:Double=0.9) extends CaffeSolver {
  def update(dmlScript:StringBuilder, layer:CaffeLayer):Unit = {
    l2reg_update(lambda, dmlScript, layer)
    if(momentum == 0) {
      // Use sgd
      if(layer.shouldUpdateWeight) dmlScript.append("\t").append(layer.weight + " = sgd::update(" + commaSep(layer.weight, layer.dW, "lr") + ")\n")
      if(layer.shouldUpdateBias) dmlScript.append("\t").append(layer.bias + " = sgd::update(" + commaSep(layer.bias, layer.dB, "lr") + ")\n")
    }
    else {
      // Use sgd_momentum
      if(layer.shouldUpdateWeight) dmlScript.append("\t").append("["+ commaSep(layer.weight, layer.weight+"_v") + "] " + 
          "= sgd_momentum::update(" + commaSep(layer.weight, layer.dW, "lr", momentum.toString, layer.weight+"_v") + ")\n")
      if(layer.shouldUpdateBias) dmlScript.append("\t").append("["+ commaSep(layer.bias, layer.bias+"_v") + "] " + 
          "= sgd_momentum::update(" + commaSep(layer.bias, layer.dB, "lr", momentum.toString, layer.bias+"_v") + ")\n")
    }
  }
  def init(dmlScript:StringBuilder, layer:CaffeLayer):Unit = {
    if(momentum != 0) {
      if(layer.shouldUpdateWeight) dmlScript.append(layer.weight+"_v = sgd_momentum::init(" + layer.weight + ")\n")
      if(layer.shouldUpdateBias) dmlScript.append(layer.bias+"_v = sgd_momentum::init(" + layer.bias + ")\n")
    }
  }
  def source(dmlScript:StringBuilder):Unit = 
    if(momentum == 0) Barista.source(dmlScript, "sgd", Barista.optimDir) 
    else Barista.source(dmlScript, "sgd_momentum", Barista.optimDir)
}

/**
 * lambda: regularization parameter
 * epsilon: Smoothing term to avoid divide by zero errors. Typical values are in the range of [1e-8, 1e-4].
 * 
 * See Adaptive Subgradient Methods for Online Learning and Stochastic Optimization, Duchi et al.
 */
class AdaGrad(lambda:Double=5e-04, epsilon:Double=1e-6) extends CaffeSolver {
  def update(dmlScript:StringBuilder, layer:CaffeLayer):Unit = {
    l2reg_update(lambda, dmlScript, layer)
    if(layer.shouldUpdateWeight) dmlScript.append("\t").append("["+ commaSep(layer.weight, layer.weight+"_cache") + "] " + 
        "= adagrad::update(" + commaSep(layer.weight, layer.dW, "lr", epsilon.toString, layer.weight+"_cache") + ")\n")
    if(layer.shouldUpdateBias) dmlScript.append("\t").append("["+ commaSep(layer.bias, layer.bias+"_cache") + "] " + 
        "= adagrad::update(" + commaSep(layer.bias, layer.dB, "lr", epsilon.toString, layer.bias+"_cache") + ")\n")
  }
  def init(dmlScript:StringBuilder, layer:CaffeLayer):Unit = {
    if(layer.shouldUpdateWeight) dmlScript.append(layer.weight+"_cache = adagrad::init(" + layer.weight + ")\n")
    if(layer.shouldUpdateBias) dmlScript.append(layer.bias+"_cache = adagrad::init(" + layer.bias + ")\n")
  }
  def source(dmlScript:StringBuilder):Unit = Barista.source(dmlScript, "adagrad", Barista.optimDir)
}

/**
 * lambda: regularization parameter
 * momentum: Momentum value. Typical values are in the range of [0.5, 0.99], usually started at the lower end and annealed towards the higher end.
 */
class Nesterov(lambda:Double=5e-04, momentum:Double=0.9) extends CaffeSolver {
  def update(dmlScript:StringBuilder, layer:CaffeLayer):Unit = {
    l2reg_update(lambda, dmlScript, layer)
    if(layer.shouldUpdateWeight) dmlScript.append("\t").append("["+ commaSep(layer.weight, layer.weight+"_v") + "] " + 
        "= sgd_nesterov::update(" + commaSep(layer.weight, layer.dW, "lr", momentum.toString, layer.weight+"_v") + ")\n")
    if(layer.shouldUpdateBias) dmlScript.append("\t").append("["+ commaSep(layer.bias, layer.bias+"_v") + "] " + 
        "= sgd_nesterov::update(" + commaSep(layer.bias, layer.dB, "lr", momentum.toString, layer.bias+"_v") + ")\n")
  }
  def init(dmlScript:StringBuilder, layer:CaffeLayer):Unit = {
    if(layer.shouldUpdateWeight) dmlScript.append(layer.weight+"_v = sgd_nesterov::init(" + layer.weight + ")\n")
    if(layer.shouldUpdateBias) dmlScript.append(layer.bias+"_v = sgd_nesterov::init(" + layer.bias + ")\n")
  }
  def source(dmlScript:StringBuilder):Unit = Barista.source(dmlScript, "sgd_nesterov", Barista.optimDir)
}