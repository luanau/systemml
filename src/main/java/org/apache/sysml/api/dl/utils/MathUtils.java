package org.apache.sysml.api.dl.utils;

public class MathUtils {
	
	public static String sqrt(String var1) {
		try {
			double var11 = Double.parseDouble(var1);
			return "" + Math.sqrt(var11);
		} catch(Exception e) {}
		return "sqrt(" + var1 + ")";
	}
	
	public static String scalarAddition(String var1, String var2) {
		double var11 = Double.MAX_VALUE;
		double var21 = Double.MAX_VALUE;
		try {
			var11 = Double.parseDouble(var1);
		} catch(Exception e) {}
		try {
			var21 = Double.parseDouble(var2);
		} catch(Exception e) {}
		if(var11 != Double.MAX_VALUE && var21 != Double.MAX_VALUE)
			return "" + (var11+var21);
		else if(var11 == 0)
			return var2;
		else if(var21 == 0)
			return var1;
		return "(" + var1 + "+" + var2 +  ")";
		
		
	}
	
	public static String scalarSubtraction(String var1, String var2) {
		try {
			double var11 = Double.parseDouble(var1);
			double var21 = Double.parseDouble(var2);
			return "" + (var11-var21);
		} catch(Exception e) {}
		return "(" + var1 + "-" + var2 + ")";
	}
	
	public static String scalarDivision(String var1, String var2) {
		double var11 = Double.MAX_VALUE;
		double var21 = Double.MAX_VALUE;
		try {
			var11 = Double.parseDouble(var1);
			
		} catch(Exception e) {}
		try {
			var21 = Double.parseDouble(var2);
		} catch(Exception e) {}
		if(var11 != Double.MAX_VALUE && var21 != Double.MAX_VALUE)
			return "" + (var11/var21);
		else if(var21 == 1)
			return var1;
		return "(" + var1 + "/" + var2 +  ")";
	}
	
	public static String scalarMultiply(String var1, String var2) {
		double var11 = Double.MAX_VALUE;
		double var21 = Double.MAX_VALUE;
		try {
			var11 = Double.parseDouble(var1);
			
		} catch(Exception e) {}
		try {
			var21 = Double.parseDouble(var2);
		} catch(Exception e) {}
		if(var11 != Double.MAX_VALUE && var21 != Double.MAX_VALUE)
			return "" + (var11*var21);
		else if(var11 == 0 || var21 == 0)
			return "0";
		return "(" + var1 + "*" + var2 +  ")";
	}
	
	public static String scalarMultiply(String var1, String var2, String var3) {
		double var11 = Double.MAX_VALUE;
		double var21 = Double.MAX_VALUE;
		double var31 = Double.MAX_VALUE;
		try {
			var11 = Double.parseDouble(var1);
			
		} catch(Exception e) {}
		try {
			var21 = Double.parseDouble(var2);
		} catch(Exception e) {}
		try {
			var31 = Double.parseDouble(var3);
		} catch(Exception e) {}
		if(var11 != Double.MAX_VALUE && var21 != Double.MAX_VALUE && var31 != Double.MAX_VALUE)
			return "" + (var11*var21*var31);
		else if(var11 == 0 || var21 == 0 || var31 == 0)
			return "0";
		else if(var11 != Double.MAX_VALUE && var21 != Double.MAX_VALUE)
			return "(" + (var11*var21) + "*" + var3+  ")";
		else if(var11 != Double.MAX_VALUE && var31 != Double.MAX_VALUE)
			return "(" + (var11*var31) + "*" + var2+  ")";
		else if(var21 != Double.MAX_VALUE && var31 != Double.MAX_VALUE)
			return "(" + (var21*var31) + "*" + var1+  ")";
		return "(" + var1 + "*" + var2 + "*" + var3+  ")";
	}
	
	public static String toInt(String var1) {
		try {
			double var11 = Double.parseDouble(var1);
			return "" + ((int)var11);
		} catch(Exception e) {}
		return var1;
	}
}
