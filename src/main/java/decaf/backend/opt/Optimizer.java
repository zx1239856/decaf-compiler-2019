package decaf.backend.opt;

import decaf.backend.dataflow.CFGBuilder;
import decaf.backend.dataflow.LivenessAnalyzer;
import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.lowlevel.tac.Simulator;
import decaf.lowlevel.tac.TacInstr;
import decaf.lowlevel.tac.TacProg;

import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;

/**
 * TAC optimization phase: optimize a TAC program.
 * <p>
 * The original decaf compiler has NO optimization, thus, we implement the transformation as identity function.
 */
public class Optimizer extends Phase<TacProg, TacProg> {
    public Optimizer(Config config) {
        super("optimizer", config);
    }

    @Override
    public TacProg transform(TacProg input) {
        var copyProp = new CopyPropOpt();
        var constProp = new ConstPropOpt();
        var liveness = new LivenessOpt();
        var peepHole = new PeepHoleOpt();
        for (int round = 0; round < 10; ++round) {
            constProp.accept(input);
            copyProp.accept(input);
            liveness.accept(input);
            peepHole.accept(input);
        }
        return input;
    }

    @Override
    public void onSucceed(TacProg program) {
        if (config.target.equals(Config.Target.PA4)) {
            // First dump the tac program to file,
            var path = config.dstPath.resolve(config.getSourceBaseName() + ".tac");
            try {
                var printer = new PrintWriter(path.toFile());
                program.printTo(printer);
                printer.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            // and then execute it using our simulator.
            var simulator = new Simulator(System.in, config.output);
            int lines = simulator.execute(program);
            path = config.dstPath.resolve(config.getSourceBaseName() + ".info");
            try (var printer = new PrintWriter(new FileWriter(path.toFile(), true))) {
                printer.format("Lines executed after optimization: %d\n", lines);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
