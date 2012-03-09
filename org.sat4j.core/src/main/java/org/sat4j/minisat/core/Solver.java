/*******************************************************************************
 * SAT4J: a SATisfiability library for Java Copyright (C) 2004, 2012 Artois University and CNRS
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
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
 *
 * Based on the original MiniSat specification from:
 *
 * An extensible SAT solver. Niklas Een and Niklas Sorensson. Proceedings of the
 * Sixth International Conference on Theory and Applications of Satisfiability
 * Testing, LNCS 2919, pp 502-518, 2003.
 *
 * See www.minisat.se for the original solver in C++.
 *
 * Contributors:
 *   CRIL - initial API and implementation
 *******************************************************************************/
package org.sat4j.minisat.core;

import static org.sat4j.core.LiteralsUtils.toDimacs;
import static org.sat4j.core.LiteralsUtils.var;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.sat4j.core.ConstrGroup;
import org.sat4j.core.LiteralsUtils;
import org.sat4j.core.Vec;
import org.sat4j.core.VecInt;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.IConstr;
import org.sat4j.specs.ISolverService;
import org.sat4j.specs.IVec;
import org.sat4j.specs.IVecInt;
import org.sat4j.specs.IteratorInt;
import org.sat4j.specs.Lbool;
import org.sat4j.specs.SearchListener;
import org.sat4j.specs.TimeoutException;

/**
 * The backbone of the library providing the modular implementation of a MiniSAT
 * (Chaff) like solver.
 * 
 * @author leberre
 */
public class Solver<D extends DataStructureFactory> implements ISolverService,
		ICDCL<D> {

	private static final long serialVersionUID = 1L;

	private static final double CLAUSE_RESCALE_FACTOR = 1e-20;

	private static final double CLAUSE_RESCALE_BOUND = 1 / CLAUSE_RESCALE_FACTOR;

	protected ICDCLLogger out;

	/**
	 * Set of original constraints.
	 */
	protected final IVec<Constr> constrs = new Vec<Constr>(); // Constr

	/**
	 * Set of learned constraints.
	 */
	protected final IVec<Constr> learnts = new Vec<Constr>(); // Clause

	/**
	 * incr?ment pour l'activit? des clauses.
	 */
	private double claInc = 1.0;

	/**
	 * decay factor pour l'activit? des clauses.
	 */
	private double claDecay = 1.0;

	/**
	 * Queue de propagation
	 */
	// head of the queue in trail ... (taken from MiniSAT 1.14)
	private int qhead = 0;

	// queue

	/**
	 * affectation en ordre chronologique
	 */
	protected final IVecInt trail = new VecInt(); // lit

	// vector

	/**
	 * indice des s?parateurs des diff?rents niveau de d?cision dans trail
	 */
	protected final IVecInt trailLim = new VecInt(); // int

	// vector

	/**
	 * S?pare les hypoth?ses incr?mentale et recherche
	 */
	protected int rootLevel;

	private int[] model = null;

	protected ILits voc;

	private IOrder order;

	private final ActivityComparator comparator = new ActivityComparator();

	private SolverStats stats = new SolverStats();

	private LearningStrategy<D> learner;

	protected volatile boolean undertimeout;

	private long timeout = Integer.MAX_VALUE;

	private boolean timeBasedTimeout = true;

	protected D dsfactory;

	private SearchParams params;

	private final IVecInt __dimacs_out = new VecInt();

	protected SearchListener slistener = new VoidTracing();

	private RestartStrategy restarter;

	private final Map<String, Counter> constrTypes = new HashMap<String, Counter>();

	private boolean isDBSimplificationAllowed = false;

	private final IVecInt learnedLiterals = new VecInt();

	private boolean verbose = false;

	private String prefix = "c ";
	private int declaredMaxVarId = 0;

	protected IVecInt dimacs2internal(IVecInt in) {
		__dimacs_out.clear();
		__dimacs_out.ensure(in.size());
		int p;
		for (int i = 0; i < in.size(); i++) {
			p = in.get(i);
			if (p == 0) {
				throw new IllegalArgumentException(
						"0 is not a valid variable identifier");
			}
			__dimacs_out.unsafePush(voc.getFromPool(p));
		}
		return __dimacs_out;
	}

	/*
	 * @since 2.3.1
	 */
	public void registerLiteral(int p) {
		voc.getFromPool(p);
	}

	/**
	 * creates a Solver without LearningListener. A learningListener must be
	 * added to the solver, else it won't backtrack!!! A data structure factory
	 * must be provided, else it won't work either.
	 */

	public Solver(LearningStrategy<D> learner, D dsf, IOrder order,
			RestartStrategy restarter) {
		this(learner, dsf, new SearchParams(), order, restarter);
	}

	public Solver(LearningStrategy<D> learner, D dsf, SearchParams params,
			IOrder order, RestartStrategy restarter) {
		this(learner, dsf, params, order, restarter, ICDCLLogger.CONSOLE);
	}

	public Solver(LearningStrategy<D> learner, D dsf, SearchParams params,
			IOrder order, RestartStrategy restarter, ICDCLLogger logger) {
		this.learner = learner;
		this.order = order;
		this.params = params;
		setDataStructureFactory(dsf);
		this.restarter = restarter;
		this.out = logger;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#setDataStructureFactory(D)
	 */
	public final void setDataStructureFactory(D dsf) {
		dsfactory = dsf;
		dsfactory.setUnitPropagationListener(this);
		dsfactory.setLearner(this);
		voc = dsf.getVocabulary();
		order.setLits(voc);
	}

	/**
	 * @since 2.2
	 */
	public boolean isVerbose() {
		return verbose;
	}

	/**
	 * @param value
	 * @since 2.2
	 */
	public void setVerbose(boolean value) {
		verbose = value;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.sat4j.minisat.core.ICDCL#setSearchListener(org.sat4j.specs.SearchListener
	 * )
	 */
	public void setSearchListener(SearchListener sl) {
		slistener = sl;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#getSearchListener()
	 */
	public SearchListener getSearchListener() {
		return slistener;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#setLearner(org.sat4j.minisat.core.
	 * LearningStrategy)
	 */
	public void setLearner(LearningStrategy<D> learner) {
		this.learner = learner;
	}

	public void setTimeout(int t) {
		timeout = t * 1000L;
		timeBasedTimeout = true;
	}

	public void setTimeoutMs(long t) {
		timeout = t;
		timeBasedTimeout = true;
	}

	public void setTimeoutOnConflicts(int count) {
		timeout = count;
		timeBasedTimeout = false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#setSearchParams(org.sat4j.minisat.core.
	 * SearchParams)
	 */
	public void setSearchParams(SearchParams sp) {
		params = sp;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.sat4j.minisat.core.ICDCL#setRestartStrategy(org.sat4j.minisat.core
	 * .RestartStrategy)
	 */
	public void setRestartStrategy(RestartStrategy restarter) {
		this.restarter = restarter;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#getRestartStrategy()
	 */
	public RestartStrategy getRestartStrategy() {
		return restarter;
	}

	public void expireTimeout() {
		undertimeout = false;
		if (timeBasedTimeout) {
			if (timer != null) {
				timer.cancel();
				timer = null;
			}
		} else {
			if (conflictCount != null) {
				conflictCount = null;
			}
		}
	}

	protected int nAssigns() {
		return trail.size();
	}

	public int nConstraints() {
		return constrs.size();
	}

	public void learn(Constr c) {
		slistener.learn(c);
		learnts.push(c);
		c.setLearnt();
		c.register();
		stats.learnedclauses++;
		switch (c.size()) {
		case 2:
			stats.learnedbinaryclauses++;
			break;
		case 3:
			stats.learnedternaryclauses++;
			break;
		default:
			// do nothing
		}
	}

	public final int decisionLevel() {
		return trailLim.size();
	}

	@Deprecated
	public int newVar() {
		int index = voc.nVars() + 1;
		voc.ensurePool(index);
		return index;
	}

	public int newVar(int howmany) {
		voc.ensurePool(howmany);
		declaredMaxVarId = howmany;
		return howmany;
	}

	public IConstr addClause(IVecInt literals) throws ContradictionException {
		IVecInt vlits = dimacs2internal(literals);
		return addConstr(dsfactory.createClause(vlits));
	}

	public boolean removeConstr(IConstr co) {
		if (co == null) {
			throw new IllegalArgumentException(
					"Reference to the constraint to remove needed!"); //$NON-NLS-1$
		}
		Constr c = (Constr) co;
		c.remove(this);
		constrs.remove(c);
		clearLearntClauses();
		String type = c.getClass().getName();
		constrTypes.get(type).dec();
		return true;
	}

	/**
	 * @since 2.1
	 */
	public boolean removeSubsumedConstr(IConstr co) {
		if (co == null) {
			throw new IllegalArgumentException(
					"Reference to the constraint to remove needed!"); //$NON-NLS-1$
		}
		if (constrs.last() != co) {
			throw new IllegalArgumentException(
					"Can only remove latest added constraint!!!"); //$NON-NLS-1$
		}
		Constr c = (Constr) co;
		c.remove(this);
		constrs.pop();
		String type = c.getClass().getName();
		constrTypes.get(type).dec();
		return true;
	}

	public void addAllClauses(IVec<IVecInt> clauses)
			throws ContradictionException {
		for (Iterator<IVecInt> iterator = clauses.iterator(); iterator
				.hasNext();) {
			addClause(iterator.next());
		}
	}

	public IConstr addAtMost(IVecInt literals, int degree)
			throws ContradictionException {
		int n = literals.size();
		IVecInt opliterals = new VecInt(n);
		for (IteratorInt iterator = literals.iterator(); iterator.hasNext();) {
			opliterals.push(-iterator.next());
		}
		return addAtLeast(opliterals, n - degree);
	}

	public IConstr addAtLeast(IVecInt literals, int degree)
			throws ContradictionException {
		IVecInt vlits = dimacs2internal(literals);
		return addConstr(dsfactory.createCardinalityConstraint(vlits, degree));
	}

	public IConstr addExactly(IVecInt literals, int n)
			throws ContradictionException {
		ConstrGroup group = new ConstrGroup(false);
		group.add(addAtMost(literals, n));
		group.add(addAtLeast(literals, n));
		return group;
	}

	@SuppressWarnings("unchecked")
	public boolean simplifyDB() {
		// Simplifie la base de clauses apres la premiere propagation des
		// clauses unitaires
		IVec<Constr>[] cs = new IVec[] { constrs, learnts };
		for (int type = 0; type < 2; type++) {
			int j = 0;
			for (int i = 0; i < cs[type].size(); i++) {
				if (cs[type].get(i).simplify()) {
					// enleve les contraintes satisfaites de la base
					cs[type].get(i).remove(this);
				} else {
					cs[type].moveTo(j++, i);
				}
			}
			cs[type].shrinkTo(j);
		}
		return true;
	}

	/**
	 * Si un mod?le est trouv?, ce vecteur contient le mod?le.
	 * 
	 * @return un mod?le de la formule.
	 */
	public int[] model() {
		if (model == null) {
			throw new UnsupportedOperationException(
					"Call the solve method first!!!"); //$NON-NLS-1$
		}
		int[] nmodel = new int[model.length];
		System.arraycopy(model, 0, nmodel, 0, model.length);
		return nmodel;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#enqueue(int)
	 */
	public boolean enqueue(int p) {
		return enqueue(p, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#enqueue(int,
	 * org.sat4j.minisat.core.Constr)
	 */
	public boolean enqueue(int p, Constr from) {
		assert p > 1;
		if (voc.isSatisfied(p)) {
			// literal is already satisfied. Skipping.
			return true;
		}
		if (voc.isFalsified(p)) {
			// conflicting enqueued assignment
			return false;
		}
		// new fact, store it
		voc.satisfies(p);
		voc.setLevel(p, decisionLevel());
		voc.setReason(p, from);
		trail.push(p);
		return true;
	}

	private boolean[] mseen = new boolean[0];

	private final IVecInt mpreason = new VecInt();

	private final IVecInt moutLearnt = new VecInt();

	/**
	 * @throws TimeoutException
	 *             if the timeout is reached during conflict analysis.
	 */
	public void analyze(Constr confl, Pair results) throws TimeoutException {
		assert confl != null;

		final boolean[] seen = mseen;
		final IVecInt outLearnt = moutLearnt;
		final IVecInt preason = mpreason;

		outLearnt.clear();
		assert outLearnt.size() == 0;
		for (int i = 0; i < seen.length; i++) {
			seen[i] = false;
		}

		int counter = 0;
		int p = ILits.UNDEFINED;

		outLearnt.push(ILits.UNDEFINED);
		// reserve de la place pour le litteral falsifie
		int outBtlevel = 0;
		IConstr prevConfl = null;

		do {
			preason.clear();
			assert confl != null;
			if (prevConfl != confl) {
				confl.calcReason(p, preason);
				learnedConstraintsDeletionStrategy.onConflictAnalysis(confl);
				// Trace reason for p
				for (int j = 0; j < preason.size(); j++) {
					int q = preason.get(j);
					order.updateVar(q);
					if (!seen[q >> 1]) {
						seen[q >> 1] = true;
						if (voc.getLevel(q) == decisionLevel()) {
							counter++;
							order.updateVarAtDecisionLevel(q);
						} else if (voc.getLevel(q) > 0) {
							// only literals assigned after decision level 0
							// part of
							// the explanation
							outLearnt.push(q ^ 1);
							outBtlevel = Math.max(outBtlevel, voc.getLevel(q));
						}
					}
				}
			}
			prevConfl = confl;
			// select next reason to look at
			do {
				p = trail.last();
				confl = voc.getReason(p);
				undoOne();
			} while (!seen[p >> 1]);
			// seen[p.var] indique que p se trouve dans outLearnt ou dans
			// le dernier niveau de d?cision
		} while (--counter > 0);

		outLearnt.set(0, p ^ 1);
		simplifier.simplify(outLearnt);

		Constr c = dsfactory.createUnregisteredClause(outLearnt);
		// slistener.learn(c);
		learnedConstraintsDeletionStrategy.onConflict(c);
		results.reason = c;

		assert outBtlevel > -1;
		results.backtrackLevel = outBtlevel;
	}

	/**
	 * Derive a subset of the assumptions causing the inconistency.
	 * 
	 * @param confl
	 *            the last conflict of the search, occuring at root level.
	 * @param assumps
	 *            the set of assumption literals
	 * @param conflictingLiteral
	 *            the literal detected conflicting while propagating
	 *            assumptions.
	 * @return a subset of assumps causing the inconsistency.
	 * @since 2.2
	 */
	public IVecInt analyzeFinalConflictInTermsOfAssumptions(Constr confl,
			IVecInt assumps, int conflictingLiteral) {
		if (assumps.size() == 0) {
			return null;
		}
		while (!trailLim.isEmpty() && trailLim.last() == trail.size()) {
			// conflict detected when assuming a value
			trailLim.pop();
		}
		final boolean[] seen = mseen;
		final IVecInt outLearnt = moutLearnt;
		final IVecInt preason = mpreason;

		outLearnt.clear();
		if (trailLim.size() == 0) {
			// conflict detected on unit clauses
			return outLearnt;
		}

		assert outLearnt.size() == 0;
		for (int i = 0; i < seen.length; i++) {
			seen[i] = false;
		}

		if (confl == null) {
			seen[conflictingLiteral >> 1] = true;
		}

		int p = ILits.UNDEFINED;
		while (confl == null && trail.size() > 0 && trailLim.size() > 0) {
			p = trail.last();
			confl = voc.getReason(p);
			undoOne();
			if (confl == null && p == (conflictingLiteral ^ 1)) {
				outLearnt.push(toDimacs(p));
			}
			if (trail.size() <= trailLim.last()) {
				trailLim.pop();
			}
		}
		if (confl == null) {
			return outLearnt;
		}
		do {

			preason.clear();
			confl.calcReason(p, preason);
			// Trace reason for p
			for (int j = 0; j < preason.size(); j++) {
				int q = preason.get(j);
				if (!seen[q >> 1]) {
					seen[q >> 1] = true;
					if (voc.getReason(q) == null && voc.getLevel(q) > 0) {
						assert assumps.contains(toDimacs(q));
						outLearnt.push(toDimacs(q));
					}
				}
			}

			// select next reason to look at
			do {
				p = trail.last();
				confl = voc.getReason(p);
				undoOne();
				if (decisionLevel() > 0 && trail.size() <= trailLim.last()) {
					trailLim.pop();
				}
			} while (trail.size() > 0 && decisionLevel() > 0
					&& (!seen[p >> 1] || confl == null));
		} while (decisionLevel() > 0);
		return outLearnt;
	}

	public static final ISimplifier NO_SIMPLIFICATION = new ISimplifier() {
		/**
         * 
         */
		private static final long serialVersionUID = 1L;

		public void simplify(IVecInt outLearnt) {
		}

		@Override
		public String toString() {
			return "No reason simplification"; //$NON-NLS-1$
		}
	};

	public final ISimplifier SIMPLE_SIMPLIFICATION = new ISimplifier() {
		/**
         * 
         */
		private static final long serialVersionUID = 1L;

		public void simplify(IVecInt conflictToReduce) {
			simpleSimplification(conflictToReduce);
		}

		@Override
		public String toString() {
			return "Simple reason simplification"; //$NON-NLS-1$
		}
	};

	public final ISimplifier EXPENSIVE_SIMPLIFICATION = new ISimplifier() {

		/**
         * 
         */
		private static final long serialVersionUID = 1L;

		public void simplify(IVecInt conflictToReduce) {
			expensiveSimplification(conflictToReduce);
		}

		@Override
		public String toString() {
			return "Expensive reason simplification"; //$NON-NLS-1$
		}
	};

	public final ISimplifier EXPENSIVE_SIMPLIFICATION_WLONLY = new ISimplifier() {

		/**
         * 
         */
		private static final long serialVersionUID = 1L;

		public void simplify(IVecInt conflictToReduce) {
			expensiveSimplificationWLOnly(conflictToReduce);
		}

		@Override
		public String toString() {
			return "Expensive reason simplification specific for WL data structure"; //$NON-NLS-1$
		}
	};

	private ISimplifier simplifier = NO_SIMPLIFICATION;

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#setSimplifier(java.lang.String)
	 */
	public void setSimplifier(SimplificationType simp) {
		Field f;
		try {
			f = Solver.class.getDeclaredField(simp.toString());
			simplifier = (ISimplifier) f.get(this);
		} catch (Exception e) {
			e.printStackTrace();
			simplifier = NO_SIMPLIFICATION;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.sat4j.minisat.core.ICDCL#setSimplifier(org.sat4j.minisat.core.Solver
	 * .ISimplifier)
	 */
	public void setSimplifier(ISimplifier simp) {
		simplifier = simp;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#getSimplifier()
	 */
	public ISimplifier getSimplifier() {
		return simplifier;
	}

	// MiniSat -- Copyright (c) 2003-2005, Niklas Een, Niklas Sorensson
	//
	// Permission is hereby granted, free of charge, to any person obtaining a
	// copy of this software and associated documentation files (the
	// "Software"), to deal in the Software without restriction, including
	// without limitation the rights to use, copy, modify, merge, publish,
	// distribute, sublicense, and/or sell copies of the Software, and to
	// permit persons to whom the Software is furnished to do so, subject to
	// the following conditions:
	//
	// The above copyright notice and this permission notice shall be included
	// in all copies or substantial portions of the Software.
	//
	// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
	// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
	// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
	// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
	// LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
	// OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
	// WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

	// Taken from MiniSAT 1.14: Simplify conflict clause (a little):
	private void simpleSimplification(IVecInt conflictToReduce) {
		int i, j, p;
		final boolean[] seen = mseen;
		IConstr r;
		for (i = j = 1; i < conflictToReduce.size(); i++) {
			r = voc.getReason(conflictToReduce.get(i));
			if (r == null || r.canBePropagatedMultipleTimes()) {
				conflictToReduce.moveTo(j++, i);
			} else {
				for (int k = 0; k < r.size(); k++) {
					p = r.get(k);
					if (!seen[p >> 1] && voc.isFalsified(p)
							&& (voc.getLevel(p) != 0)) {
						conflictToReduce.moveTo(j++, i);
						break;
					}
				}
			}
		}
		conflictToReduce.shrink(i - j);
		stats.reducedliterals += (i - j);
	}

	private final IVecInt analyzetoclear = new VecInt();

	private final IVecInt analyzestack = new VecInt();

	// Taken from MiniSAT 1.14
	private void expensiveSimplification(IVecInt conflictToReduce) {
		// Simplify conflict clause (a lot):
		//
		int i, j;
		// (maintain an abstraction of levels involved in conflict)
		analyzetoclear.clear();
		conflictToReduce.copyTo(analyzetoclear);
		for (i = 1, j = 1; i < conflictToReduce.size(); i++)
			if (voc.getReason(conflictToReduce.get(i)) == null
					|| !analyzeRemovable(conflictToReduce.get(i)))
				conflictToReduce.moveTo(j++, i);
		conflictToReduce.shrink(i - j);
		stats.reducedliterals += (i - j);
	}

	// Check if 'p' can be removed.' min_level' is used to abort early if
	// visiting literals at a level that cannot be removed.
	//
	private boolean analyzeRemovable(int p) {
		assert voc.getReason(p) != null;
		ILits lvoc = voc;
		IVecInt lanalyzestack = analyzestack;
		IVecInt lanalyzetoclear = analyzetoclear;
		lanalyzestack.clear();
		lanalyzestack.push(p);
		final boolean[] seen = mseen;
		int top = lanalyzetoclear.size();
		while (lanalyzestack.size() > 0) {
			int q = lanalyzestack.last();
			assert lvoc.getReason(q) != null;
			Constr c = lvoc.getReason(q);
			lanalyzestack.pop();
			if (c.canBePropagatedMultipleTimes()) {
				for (int j = top; j < lanalyzetoclear.size(); j++)
					seen[lanalyzetoclear.get(j) >> 1] = false;
				lanalyzetoclear.shrink(lanalyzetoclear.size() - top);
				return false;
			}
			for (int i = 0; i < c.size(); i++) {
				int l = c.get(i);
				if (!seen[var(l)] && lvoc.isFalsified(l)
						&& lvoc.getLevel(l) != 0) {
					if (lvoc.getReason(l) == null) {
						for (int j = top; j < lanalyzetoclear.size(); j++)
							seen[lanalyzetoclear.get(j) >> 1] = false;
						lanalyzetoclear.shrink(lanalyzetoclear.size() - top);
						return false;
					}
					seen[l >> 1] = true;
					lanalyzestack.push(l);
					lanalyzetoclear.push(l);
				}
			}

		}

		return true;
	}

	// Taken from MiniSAT 1.14
	private void expensiveSimplificationWLOnly(IVecInt conflictToReduce) {
		// Simplify conflict clause (a lot):
		//
		int i, j;
		// (maintain an abstraction of levels involved in conflict)
		analyzetoclear.clear();
		conflictToReduce.copyTo(analyzetoclear);
		for (i = 1, j = 1; i < conflictToReduce.size(); i++)
			if (voc.getReason(conflictToReduce.get(i)) == null
					|| !analyzeRemovableWLOnly(conflictToReduce.get(i)))
				conflictToReduce.moveTo(j++, i);
		conflictToReduce.shrink(i - j);
		stats.reducedliterals += (i - j);
	}

	// Check if 'p' can be removed.' min_level' is used to abort early if
	// visiting literals at a level that cannot be removed.
	//
	private boolean analyzeRemovableWLOnly(int p) {
		assert voc.getReason(p) != null;
		analyzestack.clear();
		analyzestack.push(p);
		final boolean[] seen = mseen;
		int top = analyzetoclear.size();
		while (analyzestack.size() > 0) {
			int q = analyzestack.last();
			assert voc.getReason(q) != null;
			Constr c = voc.getReason(q);
			analyzestack.pop();
			for (int i = 1; i < c.size(); i++) {
				int l = c.get(i);
				if (!seen[var(l)] && voc.getLevel(l) != 0) {
					if (voc.getReason(l) == null) {
						for (int j = top; j < analyzetoclear.size(); j++)
							seen[analyzetoclear.get(j) >> 1] = false;
						analyzetoclear.shrink(analyzetoclear.size() - top);
						return false;
					}
					seen[l >> 1] = true;
					analyzestack.push(l);
					analyzetoclear.push(l);
				}
			}
		}

		return true;
	}

	// END Minisat 1.14 cut and paste

	/**
     * 
     */
	protected void undoOne() {
		// gather last assigned literal
		int p = trail.last();
		assert p > 1;
		assert voc.getLevel(p) >= 0;
		int x = p >> 1;
		// unassign variable
		voc.unassign(p);
		voc.setReason(p, null);
		voc.setLevel(p, -1);
		// update heuristics value
		order.undo(x);
		// remove literal from the trail
		trail.pop();
		// update constraints on backtrack.
		// not used if the solver uses watched literals.
		IVec<Undoable> undos = voc.undos(p);
		assert undos != null;
		for (int size = undos.size(); size > 0; size--) {
			undos.last().undo(p);
			undos.pop();
		}
	}

	/**
	 * Propagate activity to a constraint
	 * 
	 * @param confl
	 *            a constraint
	 */
	public void claBumpActivity(Constr confl) {
		confl.incActivity(claInc);
		if (confl.getActivity() > CLAUSE_RESCALE_BOUND)
			claRescalActivity();
		// for (int i = 0; i < confl.size(); i++) {
		// varBumpActivity(confl.get(i));
		// }
	}

	public void varBumpActivity(int p) {
		order.updateVar(p);
	}

	private void claRescalActivity() {
		for (int i = 0; i < learnts.size(); i++) {
			learnts.get(i).rescaleBy(CLAUSE_RESCALE_FACTOR);
		}
		claInc *= CLAUSE_RESCALE_FACTOR;
	}

	private final IVec<Propagatable> watched = new Vec<Propagatable>();

	/**
	 * @return null if not conflict is found, else a conflicting constraint.
	 */
	public Constr propagate() {
		IVec<Propagatable> lwatched = watched;
		IVecInt ltrail = trail;
		ILits lvoc = voc;
		SolverStats lstats = stats;
		IOrder lorder = order;
		SearchListener lslistener = slistener;
		// ltrail.size() changes due to propagation
		// cannot cache that value.
		while (qhead < ltrail.size()) {
			lstats.propagations++;
			int p = ltrail.get(qhead++);
			lslistener.propagating(toDimacs(p), null);
			lorder.assignLiteral(p);
			// p is the literal to propagate
			// Moved original MiniSAT code to dsfactory to avoid
			// watches manipulation in counter Based clauses for instance.
			assert p > 1;
			lwatched.clear();
			lvoc.watches(p).moveTo(lwatched);
			final int size = lwatched.size();
			for (int i = 0; i < size; i++) {
				lstats.inspects++;
				// try shortcut
				// shortcut = shortcuts.get(i);
				// if (shortcut != ILits.UNDEFINED && voc.isSatisfied(shortcut))
				// {
				// voc.watch(p, watched.get(i), shortcut);
				// stats.shortcuts++;
				// continue;
				// }
				if (!lwatched.get(i).propagate(this, p)) {
					// Constraint is conflicting: copy remaining watches to
					// watches[p]
					// and return constraint
					final int sizew = lwatched.size();
					for (int j = i + 1; j < sizew; j++) {
						lvoc.watch(p, lwatched.get(j));
					}
					qhead = ltrail.size(); // propQ.clear();
					return lwatched.get(i).toConstraint();
				}
			}
		}
		return null;
	}

	void record(Constr constr) {
		constr.assertConstraint(this);
		slistener.adding(toDimacs(constr.get(0)));
		if (constr.size() == 1) {
			stats.learnedliterals++;
		} else {
			learner.learns(constr);
		}
	}

	/**
	 * @return false ssi conflit imm?diat.
	 */
	public boolean assume(int p) {
		// Precondition: assume propagation queue is empty
		assert trail.size() == qhead;
		assert !trailLim.contains(trail.size());
		trailLim.push(trail.size());
		return enqueue(p);
	}

	/**
	 * Revert to the state before the last push()
	 */
	private void cancel() {
		// assert trail.size() == qhead || !undertimeout;
		int decisionvar = trail.unsafeGet(trailLim.last());
		slistener.backtracking(toDimacs(decisionvar));
		for (int c = trail.size() - trailLim.last(); c > 0; c--) {
			undoOne();
		}
		trailLim.pop();
	}

	/**
	 * Restore literals
	 */
	private void cancelLearntLiterals(int learnedLiteralsLimit) {
		learnedLiterals.clear();
		// assert trail.size() == qhead || !undertimeout;
		while (trail.size() > learnedLiteralsLimit) {
			learnedLiterals.push(trail.last());
			undoOne();
		}
		// qhead = 0;
		// learnedLiterals = 0;
	}

	/**
	 * Cancel several levels of assumptions
	 * 
	 * @param level
	 */
	protected void cancelUntil(int level) {
		while (decisionLevel() > level) {
			cancel();
		}
		qhead = trail.size();
	}

	private final Pair analysisResult = new Pair();

	private boolean[] userbooleanmodel;

	private IVecInt unsatExplanationInTermsOfAssumptions;

	Lbool search(IVecInt assumps) {
		assert rootLevel == decisionLevel();
		stats.starts++;
		int backjumpLevel;

		// varDecay = 1 / params.varDecay;
		order.setVarDecay(1 / params.getVarDecay());
		claDecay = 1 / params.getClaDecay();

		do {
			slistener.beginLoop();
			// propage les clauses unitaires
			Constr confl = propagate();
			assert trail.size() == qhead;

			if (confl == null) {
				// No conflict found
				// simpliFYDB() prevents a correct use of
				// constraints removal.
				if (decisionLevel() == 0 && isDBSimplificationAllowed) {
					// // Simplify the set of problem clause
					// // iff rootLevel==0
					stats.rootSimplifications++;
					boolean ret = simplifyDB();
					assert ret;
				}
				// was learnts.size() - nAssigns() > nofLearnts
				// if (nofLearnts.obj >= 0 && learnts.size() > nofLearnts.obj) {
				assert nAssigns() <= voc.realnVars();
				if (nAssigns() == voc.realnVars()) {
					slistener.solutionFound();
					modelFound();
					return Lbool.TRUE;
				}
				if (restarter.shouldRestart()) {
					// Reached bound on number of conflicts
					// Force a restart
					cancelUntil(rootLevel);
					return Lbool.UNDEFINED;
				}
				if (needToReduceDB) {
					reduceDB();
					needToReduceDB = false;
					// Runtime.getRuntime().gc();
				}
				// New variable decision
				stats.decisions++;
				int p = order.select();
				if (p == ILits.UNDEFINED) {
					confl = preventTheSameDecisionsToBeMade();
					lastConflictMeansUnsat = false;
				} else {
					assert p > 1;
					slistener.assuming(toDimacs(p));
					boolean ret = assume(p);
					assert ret;
				}
			}
			if (confl != null) {
				// un conflit apparait
				stats.conflicts++;
				slistener.conflictFound(confl, decisionLevel(), trail.size());
				conflictCount.newConflict();

				if (decisionLevel() == rootLevel) {
					if (lastConflictMeansUnsat) {
						// conflict at root level, the formula is inconsistent
						unsatExplanationInTermsOfAssumptions = analyzeFinalConflictInTermsOfAssumptions(
								confl, assumps, ILits.UNDEFINED);
						return Lbool.FALSE;
					}
					return Lbool.UNDEFINED;
				}
				// analyze conflict
				try {
					analyze(confl, analysisResult);
				} catch (TimeoutException e) {
					return Lbool.UNDEFINED;
				}
				assert analysisResult.backtrackLevel < decisionLevel();
				backjumpLevel = Math.max(analysisResult.backtrackLevel,
						rootLevel);
				slistener.backjump(backjumpLevel);
				cancelUntil(backjumpLevel);
				if (backjumpLevel == rootLevel) {
					restarter.onBackjumpToRootLevel();
				}
				assert (decisionLevel() >= rootLevel)
						&& (decisionLevel() >= analysisResult.backtrackLevel);
				if (analysisResult.reason == null) {
					return Lbool.FALSE;
				}
				record(analysisResult.reason);
				analysisResult.reason = null;
				decayActivities();
			}
		} while (undertimeout);
		return Lbool.UNDEFINED; // timeout occured
	}

	private Constr preventTheSameDecisionsToBeMade() {
		IVecInt clause = new VecInt(nVars());
		int p;
		for (int i = trail.size() - 1; i >= rootLevel; i--) {
			p = trail.get(i);
			if (voc.getReason(p) == null) {
				clause.push(p ^ 1);
			}
		}
		return dsfactory.createUnregisteredClause(clause);
	}

	protected void analyzeAtRootLevel(Constr conflict) {
	}

	private final IVecInt implied = new VecInt();
	private final IVecInt decisions = new VecInt();

	private int[] fullmodel;

	/**
     * 
     */
	void modelFound() {
		IVecInt tempmodel = new VecInt(nVars());
		userbooleanmodel = new boolean[realNumberOfVariables()];
		fullmodel = null;
		for (int i = 1; i <= nVars(); i++) {
			if (voc.belongsToPool(i)) {
				int p = voc.getFromPool(i);
				if (!voc.isUnassigned(p)) {
					tempmodel.push(voc.isSatisfied(p) ? i : -i);
					userbooleanmodel[i - 1] = voc.isSatisfied(p);
					if (voc.getReason(p) == null) {
						decisions.push(tempmodel.last());
					} else {
						implied.push(tempmodel.last());
					}
				}
			}
		}
		model = new int[tempmodel.size()];
		tempmodel.copyTo(model);
		if (realNumberOfVariables() > nVars()) {
			for (int i = nVars() + 1; i <= realNumberOfVariables(); i++) {
				if (voc.belongsToPool(i)) {
					int p = voc.getFromPool(i);
					if (!voc.isUnassigned(p)) {
						tempmodel.push(voc.isSatisfied(p) ? i : -i);
						userbooleanmodel[i - 1] = voc.isSatisfied(p);
					}
				}
			}
			fullmodel = new int[tempmodel.size()];
			tempmodel.moveTo(fullmodel);
		}
		cancelUntil(rootLevel);
	}

	public int[] primeImplicant() {
		IVecInt currentD = new VecInt(decisions.size());
		decisions.copyTo(currentD);
		IVecInt assumptions = new VecInt(implied.size() + decisions.size());
		implied.copyTo(assumptions);
		decisions.copyTo(assumptions);
		IVecInt prime = new VecInt(assumptions.size());
		implied.copyTo(prime);
		for (int i = 0; i < currentD.size(); i++) {
			int p = currentD.get(i);
			assumptions.remove(p);
			assumptions.push(-p);
			try {
				if (isSatisfiable(assumptions)) {
					assumptions.pop();
					assumptions.push(-p);
				} else {
					prime.push(p);
					assumptions.pop();
					assumptions.push(p);
				}
			} catch (TimeoutException e) {
				throw new IllegalStateException("Should not timeout here", e);
			}
		}
		int[] implicant = new int[prime.size()];
		prime.copyTo(implicant);
		return implicant;
	}

	public boolean model(int var) {
		if (var <= 0 || var > realNumberOfVariables()) {
			throw new IllegalArgumentException(
					"Use a valid Dimacs var id as argument!"); //$NON-NLS-1$
		}
		if (userbooleanmodel == null) {
			throw new UnsupportedOperationException(
					"Call the solve method first!!!"); //$NON-NLS-1$
		}
		return userbooleanmodel[var - 1];
	}

	public void clearLearntClauses() {
		for (Iterator<Constr> iterator = learnts.iterator(); iterator.hasNext();)
			iterator.next().remove(this);
		learnts.clear();
		learnedLiterals.clear();
	}

	protected void reduceDB() {
		stats.reduceddb++;
		slistener.cleaning();
		learnedConstraintsDeletionStrategy.reduce(learnts);
		System.gc();
	}

	/**
	 * @param learnts
	 */
	protected void sortOnActivity() {
		learnts.sort(comparator);
	}

	/**
     * 
     */
	protected void decayActivities() {
		order.varDecayActivity();
		claDecayActivity();
	}

	/**
     * 
     */
	private void claDecayActivity() {
		claInc *= claDecay;
	}

	/**
	 * @return true iff the set of constraints is satisfiable, else false.
	 */
	public boolean isSatisfiable() throws TimeoutException {
		return isSatisfiable(VecInt.EMPTY);
	}

	/**
	 * @return true iff the set of constraints is satisfiable, else false.
	 */
	public boolean isSatisfiable(boolean global) throws TimeoutException {
		return isSatisfiable(VecInt.EMPTY, global);
	}

	private double timebegin = 0;

	private boolean needToReduceDB;

	private ConflictTimerContainer conflictCount;

	private transient Timer timer;

	public boolean isSatisfiable(IVecInt assumps) throws TimeoutException {
		return isSatisfiable(assumps, false);
	}

	public final LearnedConstraintsDeletionStrategy fixedSize(final int maxsize) {
		return new LearnedConstraintsDeletionStrategy() {

			private static final long serialVersionUID = 1L;
			private final ConflictTimer aTimer = new ConflictTimerAdapter(
					maxsize) {

				private static final long serialVersionUID = 1L;

				@Override
				public void run() {
					needToReduceDB = true;
				}
			};

			public void reduce(IVec<Constr> learnedConstrs) {
				int i, j, k;
				for (i = j = k = 0; i < learnts.size()
						&& learnts.size() - k > maxsize; i++) {
					Constr c = learnts.get(i);
					if (c.locked() || c.size() == 2) {
						learnts.set(j++, learnts.get(i));
					} else {
						c.remove(Solver.this);
						k++;
					}
				}
				for (; i < learnts.size(); i++) {
					learnts.set(j++, learnts.get(i));
				}
				if (verbose) {
					out.log(getLogPrefix() + "cleaning " + (learnts.size() - j) //$NON-NLS-1$
							+ " clauses out of " + learnts.size()); //$NON-NLS-1$ //$NON-NLS-2$
					// out.flush();
				}
				learnts.shrinkTo(j);
			}

			public void onConflictAnalysis(Constr reason) {
				// TODO Auto-generated method stub

			}

			public void onConflict(Constr outLearnt) {
				// TODO Auto-generated method stub

			}

			@Override
			public String toString() {
				return "Fixed size (" + maxsize
						+ ") learned constraints deletion strategy";
			}

			public void init() {
			}

			public ConflictTimer getTimer() {
				return aTimer;
			}
		};
	}

	/**
	 * @since 2.1
	 */
	public final LearnedConstraintsDeletionStrategy memory_based = new LearnedConstraintsDeletionStrategy() {

		private static final long serialVersionUID = 1L;

		final long memorybound = Runtime.getRuntime().freeMemory() / 10;

		private final ConflictTimer freeMem = new ConflictTimerAdapter(500) {
			private static final long serialVersionUID = 1L;

			@Override
			public void run() {
				long freemem = Runtime.getRuntime().freeMemory();
				// System.out.println("c Free memory "+freemem);
				if (freemem < memorybound) {
					// Reduce the set of learnt clauses
					needToReduceDB = true;
				}
			}
		};

		public void reduce(IVec<Constr> learnedConstrs) {
			sortOnActivity();
			int i, j;
			for (i = j = 0; i < learnts.size() / 2; i++) {
				Constr c = learnts.get(i);
				if (c.locked() || c.size() == 2) {
					learnts.set(j++, learnts.get(i));
				} else {
					c.remove(Solver.this);
				}
			}
			for (; i < learnts.size(); i++) {
				learnts.set(j++, learnts.get(i));
			}
			if (verbose) {
				out.log(getLogPrefix() + "cleaning " + (learnts.size() - j) //$NON-NLS-1$
						+ " clauses out of " + learnts.size()); //$NON-NLS-1$ //$NON-NLS-2$
				// out.flush();
			}
			learnts.shrinkTo(j);
		}

		public ConflictTimer getTimer() {
			return freeMem;
		}

		@Override
		public String toString() {
			return "Memory based learned constraints deletion strategy";
		}

		public void init() {
			// do nothing
		}

		public void onConflict(Constr constr) {
			// do nothing

		}

		public void onConflictAnalysis(Constr reason) {
			if (reason.learnt())
				claBumpActivity(reason);
		}
	};

	/**
	 * @since 2.1
	 */
	public final LearnedConstraintsDeletionStrategy glucose = new LearnedConstraintsDeletionStrategy() {

		private static final long serialVersionUID = 1L;
		private int[] flags = new int[0];
		private int flag = 0;
		// private int wall = 0;

		private final ConflictTimer clauseManagement = new ConflictTimerAdapter(
				1000) {
			private static final long serialVersionUID = 1L;
			private int nbconflict = 0;
			private static final int MAX_CLAUSE = 5000;
			private static final int INC_CLAUSE = 1000;
			private int nextbound = MAX_CLAUSE;

			@Override
			public void run() {
				nbconflict += bound();
				if (nbconflict >= nextbound) {
					nextbound += INC_CLAUSE;
					// if (nextbound > wall) {
					// nextbound = wall;
					// }
					nbconflict = 0;
					needToReduceDB = true;
				}
			}

			@Override
			public void reset() {
				super.reset();
				nextbound = MAX_CLAUSE;
				if (nbconflict >= nextbound) {
					nbconflict = 0;
					needToReduceDB = true;
				}
			}
		};

		public void reduce(IVec<Constr> learnedConstrs) {
			sortOnActivity();
			int i, j;
			for (i = j = learnedConstrs.size() / 2; i < learnedConstrs.size(); i++) {
				Constr c = learnedConstrs.get(i);
				if (c.locked() || c.getActivity() <= 2.0) {
					learnedConstrs.set(j++, learnts.get(i));
				} else {
					c.remove(Solver.this);
				}
			}
			if (verbose) {
				out.log(getLogPrefix()
						+ "cleaning " + (learnedConstrs.size() - j) //$NON-NLS-1$
						+ " clauses out of " + learnedConstrs.size() + " with flag " + flag + "/" + stats.conflicts); //$NON-NLS-1$ //$NON-NLS-2$
				// out.flush();
			}
			learnts.shrinkTo(j);

		}

		public ConflictTimer getTimer() {
			return clauseManagement;
		}

		@Override
		public String toString() {
			return "Glucose learned constraints deletion strategy";
		}

		public void init() {
			final int howmany = voc.nVars();
			// wall = constrs.size() > 10000 ? constrs.size() : 10000;
			if (flags.length <= howmany) {
				flags = new int[howmany + 1];
			}
			flag = 0;
			clauseManagement.reset();
		}

		public void onConflict(Constr constr) {
			int nblevel = 1;
			flag++;
			int currentLevel;
			for (int i = 1; i < constr.size(); i++) {
				currentLevel = voc.getLevel(constr.get(i));
				if (flags[currentLevel] != flag) {
					flags[currentLevel] = flag;
					nblevel++;
				}
			}
			constr.incActivity(nblevel);
		}

		public void onConflictAnalysis(Constr reason) {
			// do nothing
		}
	};

	protected LearnedConstraintsDeletionStrategy learnedConstraintsDeletionStrategy = glucose;

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.sat4j.minisat.core.ICDCL#setLearnedConstraintsDeletionStrategy(org
	 * .sat4j.minisat.core.Solver.LearnedConstraintsDeletionStrategy)
	 */
	public void setLearnedConstraintsDeletionStrategy(
			LearnedConstraintsDeletionStrategy lcds) {
		if (conflictCount != null) {
			conflictCount.add(lcds.getTimer());
			assert learnedConstraintsDeletionStrategy != null;
			conflictCount.remove(learnedConstraintsDeletionStrategy.getTimer());
		}
		learnedConstraintsDeletionStrategy = lcds;
	}

	private boolean lastConflictMeansUnsat;

	public boolean isSatisfiable(IVecInt assumps, boolean global)
			throws TimeoutException {
		Lbool status = Lbool.UNDEFINED;
		final int howmany = voc.nVars();
		if (mseen.length <= howmany) {
			mseen = new boolean[howmany + 1];
		}
		trail.ensure(howmany);
		trailLim.ensure(howmany);
		learnedLiterals.ensure(howmany);
		decisions.clear();
		implied.clear();
		slistener.init(this);
		slistener.start();
		model = null; // forget about previous model
		userbooleanmodel = null;
		unsatExplanationInTermsOfAssumptions = null;
		order.init();
		learnedConstraintsDeletionStrategy.init();
		int learnedLiteralsLimit = trail.size();

		// Fix for Bug SAT37
		qhead = 0;
		// Apply undos on unit literals because they are getting propagated
		// again now that qhead is 0.
		for (int i = learnedLiteralsLimit - 1; i >= 0; i--) {
			int p = trail.get(i);
			IVec<Undoable> undos = voc.undos(p);
			assert undos != null;
			for (int size = undos.size(); size > 0; size--) {
				undos.last().undo(p);
				undos.pop();
			}
		}

		// push previously learned literals
		for (IteratorInt iterator = learnedLiterals.iterator(); iterator
				.hasNext();) {
			enqueue(iterator.next());
		}

		// propagate constraints
		Constr confl = propagate();
		if (confl != null) {
			analyzeAtRootLevel(confl);
			slistener.conflictFound(confl, 0, 0);
			slistener.end(Lbool.FALSE);
			cancelUntil(0);
			cancelLearntLiterals(learnedLiteralsLimit);
			return false;
		}

		// push incremental assumptions
		for (IteratorInt iterator = assumps.iterator(); iterator.hasNext();) {
			int assump = iterator.next();
			int p = voc.getFromPool(assump);
			if ((!voc.isSatisfied(p) && !assume(p))
					|| ((confl = propagate()) != null)) {
				if (confl == null) {
					slistener.conflictFound(p);
					unsatExplanationInTermsOfAssumptions = analyzeFinalConflictInTermsOfAssumptions(
							null, assumps, p);
					unsatExplanationInTermsOfAssumptions.push(assump);
				} else {
					slistener.conflictFound(confl, decisionLevel(),
							trail.size());
					unsatExplanationInTermsOfAssumptions = analyzeFinalConflictInTermsOfAssumptions(
							confl, assumps, ILits.UNDEFINED);
				}

				slistener.end(Lbool.FALSE);
				cancelUntil(0);
				cancelLearntLiterals(learnedLiteralsLimit);
				return false;
			}
		}
		rootLevel = decisionLevel();
		// moved initialization here if new literals are added in the
		// assumptions.
		order.init(); // duplicated on purpose
		learner.init();
		boolean alreadylaunched = conflictCount != null;
		conflictCount = new ConflictTimerContainer();
		conflictCount.add(restarter);
		conflictCount.add(learnedConstraintsDeletionStrategy.getTimer());
		boolean firstTimeGlobal = false;
		if (timeBasedTimeout) {
			if (!global || timer == null) {
				firstTimeGlobal = true;
				undertimeout = true;
				TimerTask stopMe = new TimerTask() {
					@Override
					public void run() {
						undertimeout = false;
					}
				};
				timer = new Timer(true);
				timer.schedule(stopMe, timeout);

			}
		} else {
			if (!global || !alreadylaunched) {
				firstTimeGlobal = true;
				undertimeout = true;
				ConflictTimer conflictTimeout = new ConflictTimerAdapter(
						(int) timeout) {
					private static final long serialVersionUID = 1L;

					@Override
					public void run() {
						undertimeout = false;
					}
				};
				conflictCount.add(conflictTimeout);
			}
		}
		if (!global || firstTimeGlobal) {
			restarter.init(params);
			timebegin = System.currentTimeMillis();
		}
		needToReduceDB = false;
		// this is used to allow the solver to be incomplete,
		// when using a heuristics limited to a subset of variables
		lastConflictMeansUnsat = true;
		// Solve
		while ((status == Lbool.UNDEFINED) && undertimeout
				&& lastConflictMeansUnsat) {
			status = search(assumps);
			if (status == Lbool.UNDEFINED) {
				restarter.onRestart();
				slistener.restarting();
			}
		}

		cancelUntil(0);
		cancelLearntLiterals(learnedLiteralsLimit);
		if (!global && timeBasedTimeout && timer != null) {
			timer.cancel();
			timer = null;
		}
		slistener.end(status);
		if (!undertimeout) {
			String message = " Timeout (" + timeout
					+ (timeBasedTimeout ? "s" : " conflicts") + ") exceeded";
			throw new TimeoutException(message); //$NON-NLS-1$//$NON-NLS-2$
		}
		if (status == Lbool.UNDEFINED && !lastConflictMeansUnsat) {
			throw new TimeoutException("Cannot decide the satisfiability");
		}
		return status == Lbool.TRUE;
	}

	public void printInfos(PrintWriter out, String prefix) {
		out.print(prefix);
		out.println("constraints type ");
		long total = 0;
		for (Map.Entry<String, Counter> entry : constrTypes.entrySet()) {
			out.println(prefix + entry.getKey() + " => " + entry.getValue());
			total += entry.getValue().getValue();
		}
		out.print(prefix);
		out.print(total);
		out.println(" constraints processed.");
	}

	/**
	 * @since 2.1
	 */
	public void printLearntClausesInfos(PrintWriter out, String prefix) {
		Map<String, Counter> learntTypes = new HashMap<String, Counter>();
		for (Iterator<Constr> it = learnts.iterator(); it.hasNext();) {
			String type = it.next().getClass().getName();
			Counter count = learntTypes.get(type);
			if (count == null) {
				learntTypes.put(type, new Counter());
			} else {
				count.inc();
			}
		}
		out.print(prefix);
		out.println("learnt constraints type ");
		for (Map.Entry<String, Counter> entry : learntTypes.entrySet()) {
			out.println(prefix + entry.getKey() + " => " + entry.getValue());
		}
	}

	public SolverStats getStats() {
		return stats;
	}

	/**
	 * 
	 * @param myStats
	 * @since 2.2
	 */
	protected void initStats(SolverStats myStats) {
		this.stats = myStats;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#getOrder()
	 */
	public IOrder getOrder() {
		return order;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.minisat.core.ICDCL#setOrder(org.sat4j.minisat.core.IOrder)
	 */
	public void setOrder(IOrder h) {
		order = h;
		order.setLits(voc);
	}

	public ILits getVocabulary() {
		return voc;
	}

	public void reset() {
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
		trail.clear();
		trailLim.clear();
		qhead = 0;
		for (Iterator<Constr> iterator = constrs.iterator(); iterator.hasNext();)
			iterator.next().remove(this);
		constrs.clear();
		clearLearntClauses();
		voc.resetPool();
		dsfactory.reset();
		stats.reset();
		constrTypes.clear();
	}

	public int nVars() {
		if (declaredMaxVarId == 0) {
			return voc.nVars();
		}
		return declaredMaxVarId;
	}

	/**
	 * @param constr
	 *            a constraint implementing the Constr interface.
	 * @return a reference to the constraint for external use.
	 */
	protected IConstr addConstr(Constr constr) {
		if (constr == null) {
			Counter count = constrTypes.get("ignored satisfied constraints");
			if (count == null) {
				constrTypes.put("ignored satisfied constraints", new Counter());
			} else {
				count.inc();
			}
		} else {
			constrs.push(constr);
			String type = constr.getClass().getName();
			Counter count = constrTypes.get(type);
			if (count == null) {
				constrTypes.put(type, new Counter());
			} else {
				count.inc();
			}
		}
		return constr;
	}

	public DataStructureFactory getDSFactory() {
		return dsfactory;
	}

	public IVecInt getOutLearnt() {
		return moutLearnt;
	}

	/**
	 * returns the ith constraint in the solver.
	 * 
	 * @param i
	 *            the constraint number (begins at 0)
	 * @return the ith constraint
	 */
	public IConstr getIthConstr(int i) {
		return constrs.get(i);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sat4j.specs.ISolver#printStat(java.io.PrintStream,
	 * java.lang.String)
	 */
	public void printStat(PrintStream out, String prefix) {
		printStat(new PrintWriter(out, true), prefix);
	}

	public void printStat(PrintWriter out, String prefix) {
		stats.printStat(out, prefix);
		double cputime = (System.currentTimeMillis() - timebegin) / 1000;
		out.println(prefix
				+ "speed (assignments/second)\t: " + stats.propagations //$NON-NLS-1$
				/ cputime);
		order.printStat(out, prefix);
		printLearntClausesInfos(out, prefix);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	public String toString(String prefix) {
		StringBuffer stb = new StringBuffer();
		Object[] objs = { dsfactory, learner, params, order, simplifier,
				restarter, learnedConstraintsDeletionStrategy };
		stb.append(prefix);
		stb.append("--- Begin Solver configuration ---"); //$NON-NLS-1$
		stb.append("\n"); //$NON-NLS-1$
		for (Object o : objs) {
			stb.append(prefix);
			stb.append(o.toString());
			stb.append("\n"); //$NON-NLS-1$
		}
		stb.append(prefix);
		stb.append("timeout=");
		if (timeBasedTimeout) {
			stb.append(timeout / 1000);
			stb.append("s\n");
		} else {
			stb.append(timeout);
			stb.append(" conflicts\n");
		}
		stb.append(prefix);
		stb.append("DB Simplification allowed=");
		stb.append(isDBSimplificationAllowed);
		stb.append("\n");
		stb.append(prefix);
		stb.append("--- End Solver configuration ---"); //$NON-NLS-1$
		return stb.toString();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return toString(""); //$NON-NLS-1$
	}

	public int getTimeout() {
		return (int) (timeBasedTimeout ? timeout / 1000 : timeout);
	}

	/**
	 * @since 2.1
	 */
	public long getTimeoutMs() {
		if (!timeBasedTimeout) {
			throw new UnsupportedOperationException(
					"The timeout is given in number of conflicts!");
		}
		return timeout;
	}

	public void setExpectedNumberOfClauses(int nb) {
		constrs.ensure(nb);
	}

	public Map<String, Number> getStat() {
		return stats.toMap();
	}

	public int[] findModel() throws TimeoutException {
		if (isSatisfiable()) {
			return model();
		}
		// DLB findbugs ok
		// A zero length array would mean that the formula is a tautology.
		return null;
	}

	public int[] findModel(IVecInt assumps) throws TimeoutException {
		if (isSatisfiable(assumps)) {
			return model();
		}
		// DLB findbugs ok
		// A zero length array would mean that the formula is a tautology.
		return null;
	}

	public boolean isDBSimplificationAllowed() {
		return isDBSimplificationAllowed;
	}

	public void setDBSimplificationAllowed(boolean status) {
		isDBSimplificationAllowed = status;
	}

	/**
	 * @since 2.1
	 */
	public int nextFreeVarId(boolean reserve) {
		return voc.nextFreeVarId(reserve);
	}

	/**
	 * @since 2.1
	 */
	public IConstr addBlockingClause(IVecInt literals)
			throws ContradictionException {
		return addClause(literals);
	}

	/**
	 * @since 2.1
	 */
	public void unset(int p) {
		// the literal might already have been
		// removed from the trail.
		if (voc.isUnassigned(p) || trail.isEmpty()) {
			return;
		}
		int current = trail.last();
		while (current != p) {
			undoOne();
			if (trail.isEmpty()) {
				return;
			}
			current = trail.last();
		}
		undoOne();
		qhead = trail.size();
	}

	/**
	 * @since 2.2
	 */
	public void setLogPrefix(String prefix) {
		this.prefix = prefix;
	}

	/**
	 * @since 2.2
	 */
	public String getLogPrefix() {
		return prefix;
	}

	/**
	 * @since 2.2
	 */
	public IVecInt unsatExplanation() {
		IVecInt copy = new VecInt(unsatExplanationInTermsOfAssumptions.size());
		unsatExplanationInTermsOfAssumptions.copyTo(copy);
		return copy;
	}

	/**
	 * @since 2.3.1
	 */
	public int[] modelWithInternalVariables() {
		if (model == null) {
			throw new UnsupportedOperationException(
					"Call the solve method first!!!"); //$NON-NLS-1$
		}
		int[] nmodel;
		if (nVars() == realNumberOfVariables()) {
			nmodel = new int[model.length];
			System.arraycopy(model, 0, nmodel, 0, nmodel.length);
		} else {
			nmodel = new int[fullmodel.length];
			System.arraycopy(fullmodel, 0, nmodel, 0, nmodel.length);
		}

		return nmodel;
	}

	/**
	 * @since 2.3.1
	 */
	public int realNumberOfVariables() {
		return voc.nVars();
	}

	/**
	 * @since 2.3.2
	 */
	public void stop() {
		expireTimeout();
	}

	/**
	 * @since 2.3.2
	 */
	public void backtrack(int[] reason) {
		throw new UnsupportedOperationException("Not implemented yet!");
	}

	/**
	 * @since 2.3.2
	 */
	public Lbool truthValue(int literal) {
		int p = LiteralsUtils.toInternal(literal);
		if (voc.isFalsified(p))
			return Lbool.FALSE;
		if (voc.isSatisfied(p))
			return Lbool.TRUE;
		return Lbool.UNDEFINED;
	}

	/**
	 * @since 2.3.2
	 */
	public int currentDecisionLevel() {
		return decisionLevel();
	}

	/**
	 * @since 2.3.2
	 */
	public int[] getLiteralsPropagatedAt(int decisionLevel) {
		throw new UnsupportedOperationException("Not implemented yet!");
	}

	/**
	 * @since 2.3.2
	 */
	public void suggestNextLiteralToBranchOn(int l) {
		throw new UnsupportedOperationException("Not implemented yet!");
	}

	protected boolean isNeedToReduceDB() {
		return needToReduceDB;
	}

	public void setNeedToReduceDB(boolean needToReduceDB) {
		this.needToReduceDB = needToReduceDB;
	}

	public void setLogger(ICDCLLogger out) {
		this.out = out;
	}

	public ICDCLLogger getLogger() {
		return out;
	}

	public double[] getVariableHeuristics() {
		return order.getVariableHeuristics();
	}

	public IVec<Constr> getLearnedConstraints() {
		return learnts;
	}
}
