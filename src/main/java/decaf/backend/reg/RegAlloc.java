package decaf.backend.reg;

import decaf.backend.asm.AsmEmitter;
import decaf.backend.asm.SubroutineInfo;
import decaf.lowlevel.instr.PseudoInstr;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.function.Consumer;

/**
 * Register allocation.
 */
public abstract class RegAlloc implements Consumer<Pair<List<PseudoInstr>, SubroutineInfo>> {

    public RegAlloc(AsmEmitter emitter) {
        this.emitter = emitter;
    }

    /**
     * Assembly emitter.
     */
    protected AsmEmitter emitter;
}
