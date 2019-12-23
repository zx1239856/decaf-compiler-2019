package decaf.backend.asm;

import decaf.backend.reg.RegAlloc;
import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.lowlevel.log.Log;
import decaf.lowlevel.tac.TacProg;

import java.io.FileNotFoundException;
import java.io.PrintWriter;

/**
 * The assembly code generation phase: translate a TAC program to assembly code.
 */
public class Asm extends Phase<TacProg, String> {
    /**
     * Helper assembly code emitter.
     */
    protected final AsmEmitter emitter;

    /**
     * Register allocator.
     */
    protected final RegAlloc regAlloc;

    public Asm(AsmEmitter emitter, RegAlloc regAlloc, Config config) {
        super("asm: " + emitter.toString(), config);
        this.regAlloc = regAlloc;
        this.emitter = emitter;
    }

    @Override
    public String transform(TacProg prog) {
        Log.info("phase: asm");

        for (var vtbl : prog.vtables) {
            Log.info("emit vtable for %s", vtbl.className);
            emitter.emitVTable(vtbl);
        }

        emitter.emitSubroutineBegin();
        for (var func : prog.funcs) {
            Log.info("emit func for %s", func.entry.prettyString());
            regAlloc.accept(emitter.selectInstr(func));
        }

        return emitter.emitEnd();
    }

    @Override
    public void onSucceed(String code) {
        if (config.target.equals(Config.Target.PA5)) {
            var path = config.dstPath.resolve(config.getSourceBaseName() + ".s");
            try {
                var printer = new PrintWriter(path.toFile());
                printer.print(code);
                printer.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }
}
