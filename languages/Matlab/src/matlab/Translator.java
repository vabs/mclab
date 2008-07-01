package matlab;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import matlab.ast.Program;
import beaver.Parser;

/**
 * A utility for translating from Matlab source to Natlab source.
 */
public class Translator {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java matlab.Translator {basename}");
            System.exit(1);
        }
        String basename = args[0];
        try {
            BufferedReader in = new BufferedReader(new FileReader(basename + ".m"));

            ExtractionScanner scanner = new ExtractionScanner(in);
            ExtractionParser parser = new ExtractionParser();
            Program actual = (Program) parser.parse(scanner);
            PrintWriter out = new PrintWriter(new FileWriter(basename + ".n"));
            if(parser.hasError()) {
                for(TranslationProblem prob : parser.getErrors()) {
                    System.err.println(prob);
                }
            } else {
                List<TranslationProblem> problems = new ArrayList<TranslationProblem>();
                out.print(actual.translate(null, problems));
                for(TranslationProblem prob : problems) {
                    System.err.println(prob);
                }
            }
            out.close();
            in.close();
            System.exit(0);
        } catch(IOException e) {
            e.printStackTrace();
            System.exit(2);
        } catch (Parser.Exception e) {
            e.printStackTrace();
            System.exit(3);
        }
    }
}
