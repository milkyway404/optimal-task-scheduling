import java.util.List;
import org.apache.commons.cli.*;

public class Driver {

    /**
     * Main method of the project from which everything is instantiated and run.
     * Instantiates an IOParser object which creates data structures from the inputs to the method. It then retrieves
     * these data structures using getter methods and passes them to a new Solution object.
     * Finally, it sends the result it retrieves from the Solution object to the IOParser object which then outputs the
     * solution in the required format.
     * @param args Array of string of inputs, in order: input file name, processor count, [OPTIONAL]: (-p) number of cores,
     *             (-v) visualisation of search, (-o) name of output file
     */
    public static void main(String[] args) {
        String fileName = args[0];

        int numProcessors = 1;
        try {
            numProcessors = Integer.parseInt(args[1]);
        } catch(NumberFormatException e){
            System.out.println("Error number of processors invalid: Please enter the number of processors available");
            System.exit(1);
        }

        Options options = new Options();
        Option p = new Option("p", true, "numCores");
        p.setRequired(false);
        Option v = new Option("v", false, "visualization");
        v.setRequired(false);
        Option o = new Option("o", true, "output");
        o.setRequired(false);

        options.addOption(p);
        options.addOption(v);
        options.addOption(o);
        CommandLine cmd;
        CommandLineParser parser = new DefaultParser();
        int parallel = numProcessors;
        String outputFilePath = "INPUT-output.dot";

        try {
            cmd = parser.parse(options, args);
            if(cmd.hasOption("p")){
                parallel = Integer.parseInt(cmd.getOptionValue('p'));
            }
            outputFilePath = cmd.getOptionValue('o', "INPUT-output.dot");
            boolean visual = cmd.hasOption('v');
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }

        IOParser io = new IOParser(fileName, outputFilePath);
        io.read();

        List<Integer>[] inList = io.getInList();
        List<Integer>[] outList = io.getOutList();
        int[][] commCosts = io.getCommCosts();
        int[] durations = io.getDurations();

        Solution solution = new Solution();
        Task[] result = solution.run(inList, outList, commCosts, durations, numProcessors);
        io.write(result);
    }
}