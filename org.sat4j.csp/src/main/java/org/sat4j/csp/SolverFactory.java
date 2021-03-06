/*******************************************************************************
* SAT4J: a SATisfiability library for Java Copyright (C) 2004-2006 Daniel Le
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
package org.sat4j.csp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.sat4j.core.ASolverFactory;
import org.sat4j.minisat.constraints.MixedDataStructureDanielWL;
import org.sat4j.minisat.core.DataStructureFactory;
import org.sat4j.minisat.core.ILits;
import org.sat4j.minisat.core.Solver;
import org.sat4j.minisat.learning.MiniSATLearning;
import org.sat4j.minisat.orders.VarOrderHeap;
import org.sat4j.minisat.restarts.MiniSATRestarts;
import org.sat4j.pb.tools.PBAdapter;
import org.sat4j.specs.ISolver;
import org.sat4j.tools.DimacsStringSolver;

/**
 * User friendly access to pre-constructed solvers.
 * 
 * @author leberre
 */
public class SolverFactory extends ASolverFactory<ISolver> {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    // thread safe implementation of the singleton design pattern
    private static SolverFactory instance;

    /**
     * Private constructor. Use singleton method instance() instead.
     * 
     * @see #instance()
     */
    private SolverFactory() {
        super();
    }

    private static synchronized void createInstance() {
        if (instance == null) {
            instance = new SolverFactory();
        }
    }

    /**
     * Access to the single instance of the factory.
     * 
     * @return the singleton of that class.
     */
    public static SolverFactory instance() {
        if (instance == null) {
            createInstance();
        }
        return instance;
    }

    public ICspPBSatSolver createSolverByName(String solvername) {
        try {
            Class<?>[] paramtypes = {};
            Method m = this.getClass()
                    .getMethod("new" + solvername, paramtypes); //$NON-NLS-1$
            return (ICspPBSatSolver) m.invoke(null, (Object[]) null);
        } catch (SecurityException | NoSuchMethodException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            System.err.println(e.getLocalizedMessage());
        }
        return null;
    }

	/**
     * @param dsf
     *                the data structure used for representing clauses and lits
     * @return MiniSAT the data structure dsf.
     */
    public static <L extends ILits> Solver<DataStructureFactory> newMiniSAT(
            DataStructureFactory dsf) {
        MiniSATLearning<DataStructureFactory> learning = new MiniSATLearning<>();
        Solver<DataStructureFactory> solver = new Solver<>(learning, dsf,
                new VarOrderHeap(), new MiniSATRestarts());
        learning.setDataStructureFactory(solver.getDSFactory());
        learning.setVarActivityListener(solver);
        return solver;
    }


    
    /**
     * Default solver of the SolverFactory. This solver is meant to be used on
     * challenging SAT benchmarks.
     * 
     * @return the best "general purpose" SAT solver available in the factory.
     * @see #defaultSolver() the same method, polymorphic, to be called from an
     *      instance of ASolverFactory.
     */
    public static ISolver newDefault() {
    	return newSAT();
    }

    public static ICspPBSatSolver newSAT() {
    	return new CspSatSolverDecorator(org.sat4j.pb.SolverFactory.newSAT());
    }
    
    public static ICspPBSatSolver newUNSAT() {
    	return new CspSatSolverDecorator(org.sat4j.pb.SolverFactory.newUNSAT());
    }
    
    public static ICspPBSatSolver newCuttingPlanes() {
    	return new CspSatSolverDecorator(org.sat4j.pb.SolverFactory.newCuttingPlanes());
    }
    
    @Override
    public ISolver defaultSolver() {
        return newDefault();
    }

    /**
     * Small footprint SAT solver.
     * 
     * @return a SAT solver suitable for solving small/easy SAT benchmarks.
     * @see #lightSolver() the same method, polymorphic, to be called from an
     *      instance of ASolverFactory.
     */
    public static ICspPBSatSolver newLight() {
    	return new CspSatSolverDecorator(new PBAdapter(newMiniSAT(new MixedDataStructureDanielWL())));
    }

    @Override
    public ICspPBSatSolver lightSolver() {
        return newLight();
    }
    
    public static ICspPBSatSolver newDimacsOutputPB() {
    	final CspSatSolverDecorator solver = new CspSatSolverDecorator(new PBAdapter(new DimacsStringSolver()));
    	solver.setShouldOnlyDisplayEncoding(true);
		return solver;
    }

}
