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
package org.apache.sysml.api.dl.layer;

import org.apache.sysml.api.dl.utils.DLUtils;
import org.apache.sysml.runtime.DMLRuntimeException;

import caffe.Caffe.LayerParameter;
import caffe.Caffe.PoolingParameter;

public class PoolingLayer extends Layer {

	int pad_h = 0; int pad_w = 0; int stride_h = 1; int stride_w = 1;
	int kernel_h = -1; int kernel_w = -1; 
	PoolingParameter poolingParam;
	
	public PoolingLayer(LayerParameter param) throws DMLRuntimeException {
		super(param, "poolOut_");
		poolingParam = param.getPoolingParam();
		
		if(poolingParam.hasPadH())
			pad_h = poolingParam.getPadH();
		else
			pad_h = poolingParam.getPad();
		if(poolingParam.hasPadW())
			pad_w = poolingParam.getPadW();
		else
			pad_w = poolingParam.getPad();
		
		if(poolingParam.hasStrideH())
			stride_h = poolingParam.getStrideH();
		else
			stride_h = poolingParam.getStride();
		if(poolingParam.hasStrideW())
			stride_w = poolingParam.getStrideW();
		else
			stride_w = poolingParam.getStride();
		
		if(poolingParam.hasKernelH())
			kernel_h = poolingParam.getKernelH();
		else
			kernel_h = poolingParam.getKernelSize();
		
		if(poolingParam.hasKernelW())
			kernel_w = poolingParam.getKernelW();
		else
			kernel_w = poolingParam.getKernelSize();
	}

	@Override
	public String getSetupDML() throws DMLRuntimeException {
		checkInput();
		String P = "P_" + layerID + " = " + DLUtils.getP_DML(getBottomLayerOutputShape(2), pad_h, kernel_h, stride_h) + ";\n";
		String Q = "Q_" + layerID + " = " + DLUtils.getP_DML(getBottomLayerOutputShape(3), pad_w, kernel_w, stride_w) + ";\n";
		return P + Q;
	}

	String poolParamStr = "";
	
	@Override
	public String getForwardDML() throws DMLRuntimeException {
		poolParamStr = getInputShape() + ", " + "padding=[" + pad_h + "," + pad_w + "], "
				+ "stride=[" + stride_h + "," + stride_w + "], "
				+ "pool_size=[" + kernel_h + "," + kernel_w + "]";
		
		if(poolingParam.getPool() == PoolingParameter.PoolMethod.MAX) {
			return outputVar + " = max_pool(" + bottom.get(0).outputVar + ", " 
				+poolParamStr + ")";
		}
		throw new DMLRuntimeException("Unsupported pooling method:" + poolingParam.getPool().name());
	}

	@Override
	public String getBackwardDML() throws DMLRuntimeException {
		if(bottom.size() != 1) {
			throw new DMLRuntimeException("Multiple bottom layers not implemented");
		}
		return bottom.get(0).deltaVar + " = max_pool_backward(" + bottom.get(0).outputVar + ", " +
				deltaVar + "," + poolParamStr + ");";
	}

	@Override
	public String getFinalizeDML() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void updateOutputShape() throws DMLRuntimeException {
		
		output_shape.clear();
		output_shape.add(getBottomLayerOutputShape(0));
		output_shape.add(getBottomLayerOutputShape(1));
		output_shape.add("P_" + layerID);
		output_shape.add("Q_" + layerID);
	}

}