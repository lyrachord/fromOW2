/*******************************************************************************
 * SAT4J: a SATisfiability library for Java Copyright (C) 2004-2016 Daniel Le Berre
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU Lesser General Public License Version 2.1 or later (the
 * "LGPL"), in which case the provisions of the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of the LGPL, and not to allow others to use your version of
 * this file under the terms of the EPL, indicate your decision by deleting
 * the provisions above and replace them with the notice and other provisions
 * required by the LGPL. If you do not delete the provisions above, a recipient
 * may use your version of this file under the terms of the EPL or the LGPL.
 *******************************************************************************/
package org.sat4j.csp.intension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.sat4j.core.Vec;
import org.sat4j.core.VecInt;
import org.sat4j.pb.ObjectiveFunction;
import org.sat4j.specs.IVec;
import org.sat4j.specs.IVecInt;

/**
 * Encodes {@link IExpression} expressions into an {@link ICspToSatEncoder}.
 * Encode the expression using a Tseiting-like encoding ; given all the values an expression may 
 * be associated with, a boolean variable is set if and only if this is the value associated with.
 *  The process is realized in reverse topological order until the root is reached. Then, at the
 *   root level, one of the values different from zero is implied using a clause.
 * 
 * @author Emmanuel Lonca - lonca@cril.fr
 */
public class TseitinBasedIntensionCtrEncoder implements IIntensionCtrEncoder {
	
	private final ICspToSatEncoder solver;

	public TseitinBasedIntensionCtrEncoder(ICspToSatEncoder solver) {
		this.solver = solver;
	}
	
	public boolean encode(String strExpression) {
		Parser parser = new Parser(strExpression);
		parser.parse();
		final IExpression expression = parser.getExpression();
		return encodeExpression(expression);
	}
	
	@Override
	public ObjectiveFunction encodeObj(String expr) {
		Parser parser = new Parser(expr);
		parser.parse();
		final IExpression expression = parser.getExpression();
		Map<Integer, Integer> map = encodeWithTseitin(expression);
		IVecInt vars = new VecInt(map.size());
		IVec<BigInteger> coeffs = new Vec<>(map.size());
		for(Map.Entry<Integer, Integer> entry : map.entrySet()) {
			vars.push(entry.getValue());
			coeffs.push(BigInteger.valueOf(entry.getKey()));
		}
		return new ObjectiveFunction(vars, coeffs);
	}

	private boolean encodeExpression(IExpression expression) {
		Map<Integer, Integer> map = encodeWithTseitin(expression);
		for(Map.Entry<Integer, Integer> entry : map.entrySet()) {
			this.solver.addClause(new int[]{entry.getKey().equals(0) ? -entry.getValue() : entry.getValue()});
		}
		return false;
	}
	
	@SuppressWarnings("unchecked")
	private Map<Integer, Integer> encodeWithTseitin(IExpression expression) {
		Method toCall;
		Map<Integer, Integer> result;
		try {
			final String methodBodyName = expression.typeAsString().substring(0,1).toUpperCase()+expression.typeAsString().substring(1);
			toCall = getClass().getMethod("encode"+methodBodyName+"WithTseitin", IExpression.class);
			result = (Map<Integer, Integer>) toCall.invoke(this, expression);
		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			throw new IllegalArgumentException(e);
		}
		return result;
	}
	
	public Map<Integer, Integer> encodeIntegerWithTseitin(IExpression iexpr) {
		IntegerExpression expr = (IntegerExpression) iexpr;
		Map<Integer, Integer> result = new HashMap<>();
		result.put(expr.getValue(), null);
		return result;
	}
	
	public Map<Integer, Integer> encodeVarWithTseitin(IExpression iexpr) {
		VarExpression expr = (VarExpression) iexpr;
		Map<Integer, Integer> result = new HashMap<>();
		int[] domain = solver.getCspVarDomain(expr.getVar());
		for(int value : domain) {
			result.put(value, solver.getSolverVar(expr.getVar(), value));
		}
		return result;
	}
	
	@SuppressWarnings("unchecked")
	public Map<Integer, Integer> encodeOperatorWithTseitin(IExpression iexpr) {
		OperatorExpression expr = (OperatorExpression) iexpr;
		List<Map<Integer, Integer>> mappings = new ArrayList<>();
		for(IExpression operand : expr.getOperands()) mappings.add(encodeWithTseitin(operand));
		Method toCall;
		Map<Integer, Integer> result;
		try {
			final String strOpname = expr.getOperator().nameAsString().substring(0,1).toUpperCase()+expr.getOperator().nameAsString().substring(1);
			toCall = getClass().getMethod("encodeOperator"+strOpname, List.class);
			result = (Map<Integer, Integer>) toCall.invoke(this, mappings);
		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			throw new IllegalArgumentException(e);
		}
		return result;
	}
	
	public Map<Integer, Integer> encodeOperatorNeg(List<Map<Integer,Integer>> mappings) {
		Map<Integer, Integer> ret = new HashMap<>();
		for(Map.Entry<Integer, Integer> entry : mappings.get(0).entrySet()) {
			ret.put(-entry.getKey(), entry.getValue());
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorAbs(List<Map<Integer,Integer>> mappings) {
		Map<Integer, Integer> ret = new HashMap<>();
		for(Map.Entry<Integer, Integer> entry : mappings.get(0).entrySet()) {
			ret.put(Math.abs(entry.getKey()), entry.getValue());
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorAdd(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey()+entry2.getKey());
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorSub(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey()-entry2.getKey());
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorMul(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey()*entry2.getKey());
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorDiv(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey()/entry2.getKey());
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorMod(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey()%entry2.getKey());
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorSqr(List<Map<Integer,Integer>> mappings) {
		Map<Integer, Integer> ret = new HashMap<>();
		for(Map.Entry<Integer, Integer> entry : mappings.get(0).entrySet()) {
			ret.put(entry.getKey()*entry.getKey(), entry.getValue());
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorPow(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), (int)Math.pow(entry1.getKey(),entry2.getKey()));
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorMin(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), Math.min(entry1.getKey(),entry2.getKey()));
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorMax(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), Math.max(entry1.getKey(),entry2.getKey()));
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorDist(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), Math.abs(entry1.getKey()-entry2.getKey()));
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorLt(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey()<entry2.getKey()?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorLe(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey()<=entry2.getKey()?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorGt(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey()>entry2.getKey()?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorGe(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey()>=entry2.getKey()?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorNe(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), !entry1.getKey().equals(entry2.getKey())?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorEq(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey().equals(entry2.getKey())?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorSet(List<Map<Integer,Integer>> mappings) {
		throw new IllegalStateException("this operator must be translated before encoding");
	}

	public Map<Integer, Integer> encodeOperatorIn(List<Map<Integer,Integer>> mappings) {
		throw new IllegalStateException("this operator must be translated before encoding");
	}

	public Map<Integer, Integer> encodeOperatorNot(List<Map<Integer,Integer>> mappings) {
		Map<Integer, Integer> ret = new HashMap<>();
		for(Map.Entry<Integer, Integer> entry : mappings.get(0).entrySet()) {
			ret.put(entry.getKey().equals(0) ? 1 : 0, entry.getValue());
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorAnd(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), !entry1.getKey().equals(0) && !entry2.getKey().equals(0)?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorOr(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), !entry1.getKey().equals(0) || !entry2.getKey().equals(0)?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorXor(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), !entry1.getKey().equals(0) ^ !entry2.getKey().equals(0)?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorIff(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), (!entry1.getKey().equals(0)) == (!entry2.getKey().equals(0))?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorImp(List<Map<Integer,Integer>> mappings) {
		if(mappings.size() == 1) return mappings.get(0);
		if(mappings.size() > 2) throw new UnsupportedOperationException("use associativity property to build 2-arity operators");
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry1.getKey().equals(0) || !entry2.getKey().equals(0)?1:0);
			}
		}
		return ret;
	}

	public Map<Integer, Integer> encodeOperatorIf(List<Map<Integer,Integer>> mappings) {
		Map<Integer, Integer> ret = new HashMap<>();
		Iterator<Entry<Integer, Integer>> it1 = mappings.get(0).entrySet().iterator();
		while(it1.hasNext()) {
			Entry<Integer, Integer> entry1 = it1.next();
			Iterator<Entry<Integer, Integer>> it2 = mappings.get(1).entrySet().iterator();
			while(it2.hasNext()) {
				Entry<Integer, Integer> entry2 = it2.next();
				Iterator<Entry<Integer, Integer>> it3 = mappings.get(2).entrySet().iterator();
				while(it3.hasNext()) {
					Entry<Integer, Integer> entry3 = it3.next();
					buildImplVar(ret, entry1.getValue(), entry2.getValue(), entry3.getValue(), !entry1.getKey().equals(0)?entry2.getKey():entry3.getKey());
				}
			}
		}
		return ret;
	}
	
	private void buildImplVar(Map<Integer, Integer> mapping, Integer var1, Integer var2,
			int value) {
		if(var1 == null) {
			var1 = var2;
			var2 = null;
		}
		if(var1 == null) {
			mapping.put(value, null);
			return;
		}
		Integer implVar = mapping.get(value);
		if(implVar == null) {
			implVar = solver.newSatSolverVar();
			mapping.put(value, implVar);
		}
		if(var2 == null) {
			solver.addClause(new int[]{-var1, implVar});
		} else {
			solver.addClause(new int[]{-var1, -var2, implVar});
		}
	}
	
	private void buildImplVar(Map<Integer, Integer> mapping, Integer var1, Integer var2,
			Integer var3, int value) {
		Integer implVar = mapping.get(value);
		if(var1 == null) {
			var1 = var2;
			var2 = null;
		}
		if(var1 == null) {
			var1 = var3;
			var3 = null;
		}
		if(var1 == null) {
			mapping.put(value, null);
			return;
		}
		if(var2 == null) {
			var2 = var3;
			var3 = null;
		}
		if(implVar == null) {
			implVar = solver.newSatSolverVar();
			mapping.put(value, implVar);
		}
		if(var2 == null) {
			solver.addClause(new int[]{-var1, implVar});
		} else {
			if(var3 == null) {
				solver.addClause(new int[]{-var1, -var2, implVar});
			} else {
				solver.addClause(new int[]{-var1, -var3, implVar});
			}
		}
	}

	@Override
	public ICspToSatEncoder getSolver() {
		return this.solver;
	}

}
