///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (C) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
// 2007, 2008, 2009, 2010, 2014, 2015 by Peter Spirtes, Richard Scheines, Joseph   //
// Ramsey, and Clark Glymour.                                                //
//                                                                           //
// This program is free software; you can redistribute it and/or modify      //
// it under the terms of the GNU General Public License as published by      //
// the Free Software Foundation; either version 2 of the License, or         //
// (at your option) any later version.                                       //
//                                                                           //
// This program is distributed in the hope that it will be useful,           //
// but WITHOUT ANY WARRANTY; without even the implied warranty of            //
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             //
// GNU General Public License for more details.                              //
//                                                                           //
// You should have received a copy of the GNU General Public License         //
// along with this program; if not, write to the Free Software               //
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA //
///////////////////////////////////////////////////////////////////////////////

package edu.cmu.tetrad.search;

import edu.cmu.tetrad.data.IKnowledge;
import edu.cmu.tetrad.data.Knowledge2;
import edu.cmu.tetrad.graph.*;
import edu.cmu.tetrad.util.ChoiceGenerator;
import edu.cmu.tetrad.util.TetradLogger;

import java.util.*;

/**
 * This class provides the data structures and methods for carrying out the Cyclic Causal Discovery algorithm (CCD)
 * described by Thomas Richardson and Peter Spirtes in Chapter 7 of Computation, Causation, & Discovery by Glymour and
 * Cooper eds.  The comments that appear below are keyed to the algorithm specification on pp. 269-271. </p> The search
 * method returns an instance of a Graph but it also constructs two lists of node triples which represent the underlines
 * and dotted underlines that the algorithm discovers.
 *
 * @author Frank C. Wimberly
 * @author Joseph Ramsey
 */
public final class GCCD implements GraphSearch {
    private Score score;
    private IndependenceTest test;
    private int depth = -1;
    private IKnowledge knowledge;
    private List<Node> nodes;
    private boolean verbose = false;
    private boolean applyR1 = true;

    public GCCD(Score score) {
        if (score == null) throw new NullPointerException();
        this.score = score;
        this.test = new IndTestScore(score);
        this.nodes = score.getVariables();
    }

    //======================================== PUBLIC METHODS ====================================//

    /**
     * The search method assumes that the IndependenceTest provided to the constructor is a conditional independence
     * oracle for the SEM (or Bayes network) which describes the causal structure of the population. The method returns
     * a PAG instantiated as a Tetrad GaSearchGraph which represents the equivalence class of digraphs which are
     * d-separation equivalent to the digraph of the underlying model (SEM or BN). </p> Although they are not returned
     * by the search method it also computes two lists of triples which, respectively store the underlines and dotted
     * underlines of the PAG.
     */
    public Graph search() {
        Map<Triple, List<Node>> supSepsets = new HashMap<>();

        Fgs fgs = new Fgs(score);
        fgs.setVerbose(verbose);
        fgs.setNumPatternsToStore(0);
        fgs.setFaithfulnessAssumed(true);
        Graph graph = fgs.search();

        SepsetsGreedy sepsets = new SepsetsGreedy(graph, test, null, -1);
        sepsets.setDepth(5);

        Fas fas = new Fas(graph, test);
        graph = fas.search();

        graph.reorientAllWith(Endpoint.CIRCLE);

        modifiedR0(graph, sepsets);
//        stepC(graph, sepsets, null);
        stepD(graph, sepsets, supSepsets, null);
        if (stepE(supSepsets, graph)) return graph;
        stepF(graph, sepsets, supSepsets);

        // Applies R1 recursively so long as it doesn't create new
        // colliders.
        if (applyR1) {
            for (Node node : nodes) {
                orientR1(node, graph, new LinkedList<Node>());
            }
        }

        return graph;
    }

    public IKnowledge getKnowledge() {
        return knowledge;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setKnowledge(IKnowledge knowledge) {
        if (knowledge == null) {
            throw new NullPointerException();
        }
        this.knowledge = knowledge;
    }

    public long getElapsedTime() {
        return 0;
    }

    //======================================== PRIVATE METHODS ====================================//

    private boolean isArrowpointAllowed(Node from, Node to) {
        return !getKnowledge().isRequired(to.toString(), from.toString()) &&
                !getKnowledge().isForbidden(from.toString(), to.toString());
    }

    public void modifiedR0(Graph graph, SepsetProducer sepsets) {
        List<Node> nodes = graph.getNodes();

        for (Node b : nodes) {
            List<Node> adjacentNodes = graph.getAdjacentNodes(b);

            if (adjacentNodes.size() < 2) {
                continue;
            }

            ChoiceGenerator cg = new ChoiceGenerator(adjacentNodes.size(), 2);
            int[] combination;

            while ((combination = cg.next()) != null) {
                Node a = adjacentNodes.get(combination[0]);
                Node c = adjacentNodes.get(combination[1]);

                if (!graph.isAdjacentTo(a, c)) {
                    List<Node> sepset = sepsets.getSepset(a, c);

                    if (sepset != null && !sepset.contains(b) && !test.isIndependent(a, c, b)) {
                        graph.removeEdge(a, b);
                        graph.removeEdge(c, b);
                        graph.addDirectedEdge(a, b);
                        graph.addDirectedEdge(c, b);
                    } else {
                        graph.addUnderlineTriple(a, b, c);
                    }
                }
            }
        }

    }

    private void stepC(Graph psi, SepsetProducer sepsets, SepsetMap sepsetsFromFas) {
        TetradLogger.getInstance().log("info", "\nStep C");

        EDGE:
        for (Edge edge : psi.getEdges()) {
            Node x = edge.getNode1();
            Node y = edge.getNode2();

            List<Node> adjx = psi.getAdjacentNodes(x);
            List<Node> adjy = psi.getAdjacentNodes(y);

            for (Node node : adjx) {
                if (psi.getEdge(node, x).getProximalEndpoint(x) == Endpoint.ARROW && psi.isUnderlineTriple(y, x, node)) {
                    continue EDGE;
                }
            }

            int count = 0;

            // Check each A
            for (Node a : nodes) {
                if (a == x) continue;
                if (a == y) continue;

                // Orientable...
                if (psi.getEndpoint(y, x) != Endpoint.CIRCLE) continue;

                //...A is not adjacent to X and A is not adjacent to Y...
                if (adjx.contains(a)) continue;
                if (adjy.contains(a)) continue;

                //...X is not in sepset<A, Y>...
                List<Node> sepset = sepsets.getSepset(a, y);
//                if (sepset == null && sepsetsFromFas != null) sepset = sepsetsFromFas.get(a, y);

                if (sepset.contains(x)) continue;

                if (!sepsets.isIndependent(a, x, sepset)) {
                    count++;
                }
            }

            if (count >= 3) {
                psi.setEndpoint(y, x, Endpoint.ARROW);
                psi.setEndpoint(x, y, Endpoint.TAIL);
            }
        }
    }

    private void stepD(Graph psi, SepsetProducer sepsets, Map<Triple, List<Node>> supSepsets, SepsetMap fasSepsets) {
        TetradLogger.getInstance().log("info", "\nStep D");

        Map<Node, List<Node>> local = new HashMap<>();

        for (Node node : psi.getNodes()) {
            local.put(node, local(psi, node));
        }

        int m = 1;

        //maxCountLocalMinusSep is the largest cardinality of all sets of the
        //form Loacl(psi,A)\(SepSet<A,C> union {B,C})
        while (maxCountLocalMinusSep(psi, sepsets, local) >= m) {
            for (Node b : nodes) {
                List<Node> adj = psi.getAdjacentNodes(b);

                if (adj.size() < 2) continue;

                ChoiceGenerator gen1 = new ChoiceGenerator(adj.size(), 2);
                int[] choice1;

                while ((choice1 = gen1.next()) != null) {
                    Node a = adj.get(choice1[0]);
                    Node c = adj.get(choice1[1]);

                    if (psi.isAdjacentTo(a, c)) {
                        continue;
                    }

                    if (b == c || b == a) {
                        continue;
                    }

                    // This should never happen..
                    if (supSepsets.get(new Triple(a, b, c)) != null) {
                        continue;
                    }

                    // A-->B<--C
                    if (!psi.isDefCollider(a, b, c)) {
                        continue;
                    }

                    //Compute the number of elements (count)
                    //in Local(psi,A)\(sepset<A,C> union {B,C})
                    Set<Node> localMinusSep = countLocalMinusSep(sepsets, local, a, b, c);

                    int count = localMinusSep.size();

                    if (count < m) {
                        continue; //If not >= m skip to next triple.
                    }

                    //Compute the set T (setT) with m elements which is a subset of
                    //Local(psi,A)\(sepset<A,C> union {B,C})
                    Object[] v = new Object[count];
                    for (int i = 0; i < count; i++) {
                        v[i] = (localMinusSep.toArray())[i];
                    }

                    ChoiceGenerator generator = new ChoiceGenerator(count, m);
                    int[] choice;

                    while ((choice = generator.next()) != null) {
                        Set<Node> setT = new LinkedHashSet<>();
                        for (int i = 0; i < m; i++) {
                            setT.add((Node) v[choice[i]]);
                        }

                        setT.add(b);
                        List<Node> sepset = sepsets.getSepset(a, c);
                        if (sepset == null && fasSepsets != null) sepset = fasSepsets.get(a, c);
                        setT.addAll(sepset);

                        List<Node> listT = new ArrayList<>(setT);

                        //Note:  B is a collider between A and C (see above).
                        //If anode and cnode are d-separated given T union
                        //sep[a][c] union {bnode} create a dotted underline triple
                        //<A,B,C> and record T union sepset<A,C> union {B} in
                        //supsepset<A,B,C> and in supsepset<C,B,A>

                        if (test.isIndependent(a, c, listT)) {
                            supSepsets.put(new Triple(a, b, c), listT);

                            psi.addDottedUnderlineTriple(a, b, c);
                            TetradLogger.getInstance().log("underlines", "Adding dotted underline: " +
                                    new Triple(a, b, c));

                            break;
                        }
                    }
                }
            }

            m++;
        }
    }

    /**
     * Computes and returns the size (cardinality) of the largest set of the form Local(psi,A)\(SepSet<A,C> union {B,C})
     * where B is a collider between A and C and where A and C are not adjacent.  A, B and C should not be a dotted
     * underline triple.
     */
    private static int maxCountLocalMinusSep(Graph psi, SepsetProducer sep,
                                             Map<Node, List<Node>> loc) {
        List<Node> nodes = psi.getNodes();
        int maxCount = -1;

        for (Node b : nodes) {
            List<Node> adjacentNodes = psi.getAdjacentNodes(b);

            if (adjacentNodes.size() < 2) {
                continue;
            }

            ChoiceGenerator cg = new ChoiceGenerator(adjacentNodes.size(), 2);
            int[] combination;

            while ((combination = cg.next()) != null) {
                Node a = adjacentNodes.get(combination[0]);
                Node c = adjacentNodes.get(combination[1]);

                if (psi.isAdjacentTo(a, c)) {
                    continue;
                }

                // Want B to be a collider between A and C but not for
                //A, B, and C to be an underline triple.
                if (psi.isUnderlineTriple(a, b, c)) {
                    continue;
                }

                //Is B a collider between A and C?
                if (!psi.isDefCollider(a, b, c)) {
                    continue;
                }

                Set<Node> localMinusSep = countLocalMinusSep(sep, loc,
                        a, b, c);
                int count = localMinusSep.size();

                if (count > maxCount) {
                    maxCount = count;
                }
            }
        }

        return maxCount;
    }

    /**
     * For a given GaSearchGraph psi and for a given set of sepsets, each of which is associated with a pair of vertices
     * A and C, computes and returns the set Local(psi,A)\(SepSet<A,C> union {B,C}).
     */
    private static Set<Node> countLocalMinusSep(SepsetProducer sepset,
                                                Map<Node, List<Node>> local, Node anode,
                                                Node bnode, Node cnode) {
        Set<Node> localMinusSep = new HashSet<>();
        localMinusSep.addAll(local.get(anode));
        List<Node> sepset1 = sepset.getSepset(anode, cnode);
        localMinusSep.removeAll(sepset1);
        localMinusSep.remove(bnode);
        localMinusSep.remove(cnode);

        return localMinusSep;
    }


    private boolean stepE(Map<Triple, List<Node>> supSepset, Graph psi) {
        TetradLogger.getInstance().log("info", "\nStep E");

        if (nodes.size() < 4) {
            return true;
        }

        for (Triple triple : psi.getDottedUnderlines()) {
            Node a = triple.getX();
            Node b = triple.getY();

            List<Node> aAdj = psi.getAdjacentNodes(a);

            for (Node d : aAdj) {
                if (d == b) continue;

                if (supSepset.get(triple).contains(d)) {
                    if (psi.getEndpoint(b, d) != Endpoint.CIRCLE) {
                        continue;
                    }

                    // Orient B*-oD as B*-D
                    psi.setEndpoint(b, d, Endpoint.TAIL);
                } else {
                    if (psi.getEndpoint(d, b) == Endpoint.ARROW) {
                        continue;
                    }

                    if (psi.getEndpoint(b, d) != Endpoint.CIRCLE) {
                        continue;
                    }

                    // Or orient Bo-oD or B-oD as B->D...
                    psi.removeEdge(b, d);
                    psi.addDirectedEdge(b, d);
                }

            }

            for (Node d : aAdj) {
                if (d == b) continue;

                if (supSepset.get(triple).contains(d)) {
                    if (psi.getEndpoint(b, d) != Endpoint.CIRCLE) {
                        continue;
                    }

                    // Orient B*-oD as B*-D
                    psi.setEndpoint(b, d, Endpoint.TAIL);
                } else {
                    if (psi.getEndpoint(d, b) == Endpoint.ARROW) {
                        continue;
                    }

                    if (psi.getEndpoint(b, d) != Endpoint.CIRCLE) {
                        continue;
                    }

                    // Or orient Bo-oD or B-oD as B->D...
                    psi.removeEdge(b, d);
                    psi.addDirectedEdge(b, d);
                }
            }
        }

        return false;
    }


    private void stepF(Graph psi, SepsetProducer sepsets, Map<Triple, List<Node>> supSepsets) {
        for (Triple triple : psi.getDottedUnderlines()) {
            Node a = triple.getX();
            Node b = triple.getY();
            Node c = triple.getZ();

            Set<Node> adj = new HashSet<>(psi.getAdjacentNodes(a));
            adj.addAll(psi.getAdjacentNodes(c));

            for (Node d : adj) {
                if (psi.getEndpoint(b, d) != Endpoint.CIRCLE) {
                    continue;
                }

                if (psi.getEndpoint(d, b) == Endpoint.ARROW) {
                    continue;
                }

                //...and D is not adjacent to both A and C in psi...
                if (psi.isAdjacentTo(a, d) && psi.isAdjacentTo(c, d)) {
                    continue;
                }

                //...and B and D are adjacent...
                if (!psi.isAdjacentTo(b, d)) {
                    continue;
                }

                Set<Node> supSepUnionD = new HashSet<>();
                supSepUnionD.add(d);
                supSepUnionD.addAll(supSepsets.get(triple));
                List<Node> listSupSepUnionD = new ArrayList<>(supSepUnionD);

                //If A and C are a pair of vertices d-connected given
                //SupSepset<A,B,C> union {D} then orient Bo-oD or B-oD
                //as B->D in psi.
                if (!sepsets.isIndependent(a, c, listSupSepUnionD)) {
                    psi.removeEdge(b, d);
                    psi.addDirectedEdge(b, d);
                }
            }
        }
    }

    private List<Node> local(Graph psi, Node z) {
        List<Node> local = new ArrayList<>();

        //Is X p-adjacent to v in psi?
        for (Node x : nodes) {
            if (x == z) {
                continue;
            }

            if (psi.isAdjacentTo(z, x)) {
                local.add(x);
            }

            //or is there a collider between X and v in psi?
            for (Node y : nodes) {
                if (y == z || y == x) {
                    continue;
                }

                if (psi.isDefCollider(x, y, z)) {
                    if (!local.contains(x)) {
                        local.add(x);
                    }
                }
            }
        }

        return local;
    }

    private boolean orientR1(Node B, Graph graph, List<Node> path) {
        if (path.contains(B)) return true;
        path.add(B);

        List<Node> adj = graph.getAdjacentNodes(B);

        if (adj.size() < 2) {
            return true;
        }

        ChoiceGenerator cg = new ChoiceGenerator(adj.size(), 2);
        int[] combination;

        while ((combination = cg.next()) != null) {
            Node A = adj.get(combination[0]);
            Node C = adj.get(combination[1]);

            if (ruleR1(A, B, C, graph)) {
                if (!orientR1(C, graph, path)) {
                    graph.removeEdge(B, C);
                    graph.addUndirectedEdge(B, C);
                    return false;
                }
            }

            if (ruleR1(C, B, A, graph)) {
                if (!orientR1(A, graph, path)) {
                    graph.removeEdge(B, C);
                    graph.addUndirectedEdge(B, C);
                    return false;
                }
            } else {
            }
        }

        return true;
    }

    private boolean ruleR1(Node a, Node b, Node c, Graph graph) {
        if (graph.isAdjacentTo(a, c)) {
            return false;
        }

        if (graph.getEndpoint(a, b) == Endpoint.ARROW && graph.getEndpoint(c, b) == Endpoint.CIRCLE) {


            if (!graph.isUnderlineTriple(a, b, c)) {
                return false;
            }

            for (Node n : graph.getAdjacentNodes(c)) {
                if (n == b) continue;
                if (graph.isUnderlineTriple(b, c, n)) {
                    return false;
                }
            }

            graph.removeEdge(b, c);
            graph.addDirectedEdge(b, c);

            return true;
        }

        return false;
    }

    public boolean isApplyR1() {
        return applyR1;
    }

    public void setApplyR1(boolean applyR1) {
        this.applyR1 = applyR1;
    }
}






