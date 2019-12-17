package decaf.backend.dataflow;

import decaf.lowlevel.instr.PseudoInstr;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

/**
 * Control flow graph.
 * <p>
 * In a control flow graph, the nodes are basic blocks, and an edge {@code (i, j)} indicates that basic block {@code j}
 * is a reachable successor of basic block {@code i}.
 *
 * @param <I> type of the instruction stored in the block
 */
public class CFG<I extends PseudoInstr> implements Iterable<BasicBlock<I>> {

    /**
     * Nodes.
     */
    public final List<BasicBlock<I>> nodes;

    /**
     * Edges.
     */
    public final List<Pair<Integer, Integer>> edges;

    // fst: prev, snd: succ
    private List<Pair<Set<Integer>, Set<Integer>>> links;

    CFG(List<BasicBlock<I>> nodes, List<Pair<Integer, Integer>> edges) {
        this.nodes = new ArrayList<>();
        this.edges = new ArrayList<>();

        links = new ArrayList<>();
        for (var i = 0; i < nodes.size(); i++) {
            links.add(Pair.of(new TreeSet<>(), new TreeSet<>()));
        }

        for (var edge : edges) {
            var u = edge.getLeft();
            var v = edge.getRight();
            links.get(u).getRight().add(v); // u -> v
            links.get(v).getLeft().add(u); // v <- u
        }

        List<Pair<Set<Integer>, Set<Integer>>> final_links = new ArrayList<>();

        List<Integer> indexMapping = new ArrayList<>(nodes.size());

        var visited = new ArrayList<Boolean>(nodes.size());
        var reachable = new ArrayList<Boolean>(nodes.size());
        for(int i = 0; i < nodes.size(); ++i) {
            visited.add(i, false);
            reachable.add(i, false);
        }
        var queue = new ArrayDeque<Integer>();
        // bfs to test connectivity
        if(nodes.size() > 0) {
            queue.push(0);
            reachable.set(0, true);
        }
        while(!queue.isEmpty()) {
            int cand = queue.remove();
            if(!visited.get(cand)) {
                queue.addAll(getSucc(cand));
                if(reachable.get(cand)) {
                    for(var succ : getSucc(cand))
                        reachable.set(succ, true);
                }
                visited.set(cand, true);
            }
        }

        // remove nodes with zero in-degree (except block zero)
        for(int i = 0; i < nodes.size(); ++i) {
            if(reachable.get(i)) {
                indexMapping.add(this.nodes.size());
                nodes.get(i).id = this.nodes.size();
                this.nodes.add(nodes.get(i));
                final_links.add(Pair.of(new TreeSet<>(), new TreeSet<>()));
            } else
                indexMapping.add(-1);
        }

        for(var edge : edges) {
            var u = edge.getLeft();
            var v = edge.getRight();
            if(reachable.get(u) && reachable.get(v)) {
                u = indexMapping.get(u);
                v = indexMapping.get(v);
                this.edges.add(Pair.of(u, v));
                final_links.get(u).getRight().add(v); // u -> v
                final_links.get(v).getLeft().add(u); // v <- u
            }
        }

        this.links = final_links;
    }

    /**
     * Get basic block by id.
     *
     * @param id basic block id
     * @return basic block
     */
    public BasicBlock<I> getBlock(int id) {
        return nodes.get(id);
    }

    /**
     * Get predecessors.
     *
     * @param id basic block id
     * @return its predecessors
     */
    public Set<Integer> getPrev(int id) {
        return links.get(id).getLeft();
    }

    /**
     * Get successors.
     *
     * @param id basic block id
     * @return its successors
     */
    public Set<Integer> getSucc(int id) {
        return links.get(id).getRight();
    }

    /**
     * Get in-degree.
     *
     * @param id basic block id
     * @return its in-degree
     */
    public int getInDegree(int id) {
        return links.get(id).getLeft().size();
    }

    /**
     * Get out-degree.
     *
     * @param id basic block id
     * @return its out-degree
     */
    public int getOutDegree(int id) {
        return links.get(id).getRight().size();
    }

    @Override
    public Iterator<BasicBlock<I>> iterator() {
        return nodes.iterator();
    }
}
