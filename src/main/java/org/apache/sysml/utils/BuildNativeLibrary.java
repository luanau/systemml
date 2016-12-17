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

package org.apache.sysml.utils;

import java.io.File;
import java.io.IOException;
import org.apache.sysml.api.DMLScript;

public class BuildNativeLibrary {
	// Used to compile systemml.cpp via pip, run following command:
	// cd `python -c 'import imp; import os; print imp.find_module("systemml")[1]'`
	// java -classpath systemml-java/systemml*.jar org.apache.sysml.utils.BuildNativeLibrary [pip-directory]
	// This is useful as it checks for ENABLE_NATIVE_BLAS as well
	public static void main(String [] args) throws InterruptedException, IOException {
		if(DMLScript.ENABLE_NATIVE_BLAS) {
			String pipDirectory = System.getProperty("user.dir"); 
			if(args.length >= 1)
				pipDirectory = args[0];
			String cppDirectory = pipDirectory + File.separator + "systemml-cpp";
			String cppFile = pipDirectory + File.separator + "systemml-cpp" + File.separator + "systemml.cpp";
			
			String javaHome = System.getenv("JAVA_HOME");
			if(javaHome == null) {
				System.out.println("To build native library, JAVA_HOME needs to be set. Enter JAVA_HOME path or press enter to skip:");
				javaHome = System.console().readLine().trim();
				if(javaHome.equals("")) {
					System.out.println("Skipping the build of native systemml library.");
					return;
				}
			}
			String cmd = null;
			
			// First check whether MKL is available
			try {
				System.loadLibrary("mkl_rt");
				String mklRoot = System.getenv("MKLROOT");
				if(mklRoot == null) {
					System.out.println("To build native library, MKLROOT needs to be set. Enter MKLROOT path or press enter to skip:");
					mklRoot = System.console().readLine().trim();
					if(mklRoot.equals("")) {
						System.out.println("Skipping the build of native systemml library.");
						return;
					}
				}
				boolean is64bit = System.getProperty("sun.arch.data.model").contains("64");
				String OS = System.getProperty("os.name", "generic").toLowerCase();
				if ((OS.indexOf("mac") >= 0) || (OS.indexOf("darwin") >= 0)) {
					// TODO:
				}
				else if (OS.indexOf("win") >= 0) {
					// TODO:
				}
				else if (OS.indexOf("nux") >= 0) {
					String bitRelatedFlags = " -m32 -L" + mklRoot + "/lib/ia32 ";
					if(is64bit)
						bitRelatedFlags = " -m64 -L" + mklRoot + "/lib/intel64 ";
					String includeHeaders = " -I" + cppDirectory + " -I" + javaHome + "/include -I" + javaHome + "/include/linux " 
						+ "-I" + mklRoot + "/include ";
					String otherFlags = " -O3 " + bitRelatedFlags;
					String linkerFlags = " -Wl,--no-as-needed -lmkl_rt -lpthread -lm -ldl ";
					cmd = "g++ -shared -fPIC -o libsystemml.so " + cppFile + includeHeaders + otherFlags + linkerFlags;
				}
				else {
					System.out.println("Unsupported OS. Skipping the build of native systemml library.");
					return;
				}
				
			}
			catch (UnsatisfiedLinkError e) { }
			
			if(cmd == null) {
				// Try checking for OpenBLAS
			}
			
			if(cmd != null) {
				System.out.println("To build systemml native library, executing command:\n" + cmd + "\n");
				
				(new ProcessBuilder().command(cmd).inheritIO().start()).waitFor();
			}
			return;
		}
		else {
			System.out.println("Native BLAS is disabled in current version. Skipping the build of native systemml library.");
		}
	}
}
