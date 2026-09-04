import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

public class BlapInterpreter {
    private static final HashMap<String, Object> variables = new HashMap<>();
    private static ArrayList<Object> kernel = new ArrayList<>();
    private static int currentLine = 0;
    private static int lastComparison = 0;

    private static enum Boolean {
        TRUE,
        FALSE;
        static Boolean parse(String val) throws Exception {
            switch (val) {
                case "TRUE" -> {
                    return Boolean.TRUE;
                }
                case "FALSE" -> {
                    return Boolean.FALSE;
                }
                default -> throw new Exception("Cannot parse!");
            }
        }
    }

    private static class Break extends RuntimeException {}
    private static class Continue extends RuntimeException {}

    private static class Return extends RuntimeException {
        final Object value;
        Return(Object value) {
            this.value = value;
        }
    }

    private static final record Command(int lineNum, String keyword, String[] parts) {}
    private static final record Function(List<String> params, List<Command> commands) {}

    public static void main(String[] args) {
        if (args.length != 1 || !args[0].endsWith(".blap")) {
            error("Usage: java BlapInterpreter <BLAP file>", 0);
            return;
        }
        String filePath = args[0];
        String code;
        try {
            code = Files.readString(Path.of(filePath).toAbsolutePath());
        } catch (IOException e) {
            error("Error: Could not read file " + filePath, 0);
            return;
        }

        code = Pattern.compile("(?s)##.*?##").matcher(code).replaceAll(matchResult -> {
            long newlineCount = matchResult.group().chars().filter(ch -> ch == '\n').count();
            return "\n".repeat((int) newlineCount);
        });

        organizeCode(code);
    }

    private static void organizeCode(String code) {
        variables.put("NLN", "\n");
        variables.put("RETF", null);
        variables.put("NULL", null);
        variables.put("KRNL_GTLTST", null);

        List<Command> commands = new ArrayList<>();
        StringTokenizer st = new StringTokenizer(code, ";");
        int line = 0;

        while (st.hasMoreTokens()) {
            line++;
            String statement = st.nextToken().trim();
            if (statement.isEmpty() || statement.startsWith("-#")) continue;

            String[] parts = statement.split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 3);

            if (parts[0].equals("db") && parts.length > 1 && parts[1].contains(":")) {
                String[] funcSig = parts[1].split(":");
                String funcName = funcSig[0];
                
                List<String> params = new ArrayList<>();
                for (int p = 1; p < funcSig.length; p++) {
                    params.add(funcSig[p]);
                }
                
                List<Command> funcBody = new ArrayList<>();
                boolean foundEnd = false;
                
                while (st.hasMoreTokens()) {
                    line++;
                    String fStatement = st.nextToken().trim();
                    if (fStatement.isEmpty() || fStatement.startsWith("-#")) continue;
                    
                    String[] fParts = fStatement.split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 3);
                    
                    // Check if function ends
                    if (fParts[0].equals("end") && fParts.length > 1 && fParts[1].equals(funcName)) {
                        foundEnd = true;
                        break;
                    }
                    
                    funcBody.add(new Command(line, fParts[0], fParts));
                }
                
                if (!foundEnd) {
                    error("Unterminated function: " + funcName + " (missing 'end " + funcName + ";')", line);
                }
                
                variables.put(funcName, new Function(params, funcBody));
                continue; // Skip adding the function definition block to main execution
            }

            commands.add(new Command(line, parts[0], parts));
        }

        runCode(commands);
    }

    private static void runCode(List<Command> commands) {
        int i = 0;
        while (i < commands.size()) {
            Command cmd = commands.get(i);
            currentLine = cmd.lineNum();

            if (cmd.keyword().equals("loop-begin")) {
                int depth = 1;
                int endIdx = i + 1;
                while (endIdx < commands.size()) {
                    if (commands.get(endIdx).keyword().equals("loop-begin")) {
                        depth++;
                    } else if (commands.get(endIdx).keyword().equals("loop-end")) {
                        depth--;
                        if (depth == 0) break;
                    }
                    endIdx++;
                }

                if (depth != 0) {
                    error("Unmatched loop-begin block", cmd.lineNum());
                }

                List<Command> loopBodyRaw = commands.subList(i + 1, endIdx);
                List<Command> alwCommands = new ArrayList<>();
                List<Command> loopBody = new ArrayList<>();


                for (Command c : loopBodyRaw) {
                    if (c.keyword().equals("alw")) {
                        if (c.parts().length < 2) {
                            error("alw requires a command to schedule", c.lineNum());
                        }
                        String targetCommandStr = c.parts()[1] + (c.parts().length > 2 ? " " + c.parts()[2] : "");
                        String[] targetParts = targetCommandStr.split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 3);
                        alwCommands.add(new Command(c.lineNum(), targetParts[0], targetParts));
                    } else {
                        loopBody.add(c);
                    }
                }

                String varName = cmd.parts()[1];
                String[] sequence = cmd.parts()[2].split("\\s+");
                
                double start = extractNumber(sequence[0]);
                double end = extractNumber(sequence[1]);
                double step = extractNumber(sequence[2]);

                for (double val = start; val < end; val += step) {
                    if (val == (int) val) {
                        variables.put(varName, (int) val);
                    } else {
                        variables.put(varName, val);
                    }

                    boolean isBreak = false;
                    try {
                        runCode(loopBody);
                    } catch (Continue c) {} 
                      catch (Break b) {
                        isBreak = true;
                        break;
                    } finally {
                        if (!isBreak) {
                            for (Command alwCmd : alwCommands) {
                                currentLine = alwCmd.lineNum();
                                runCommand(alwCmd);
                            }
                        }
                    }
                }

                i = endIdx + 1;
            } else {
                runCommand(cmd);
                i++;
            }
        }
    }

    private static void runCommand(Command cmd) {
        if (!kernel.isEmpty()) variables.put("KRNL_GTLTST", kernel.getLast());
        String[] parts = cmd.parts();
        switch (cmd.keyword()) {
            case "use" -> main(new String[] {parts[1]});
            case "alw" -> error("alw can only be used in a loop!", cmd.lineNum());
            case "db" -> db(parts);
            case "add" -> add(parts);
            case "sub" -> sub(parts);
            case "mul" -> mul(parts);
            case "div" -> div(parts);
            case "pow" -> pow(parts);
            case "mod" -> mod(parts);
            case "flr" -> floor(parts);
            case "ceil" -> ceil(parts);
            case "ascii" -> ascii(parts);
            case "sy" -> sy(parts);
            case "mv" -> mv(parts);
            case "cl" -> cl(parts);
            case "cm" -> cm(parts);
            case "j" -> j(parts);
            case "mknum" -> {
                try {
                    variables.put(parts[1], Long.parseLong(String.valueOf(variables.get(parts[1]))));
                } catch (NumberFormatException e) {
                    try {
                        variables.put(parts[1], Double.parseDouble(String.valueOf(variables.get(parts[1]))));
                    } catch (NumberFormatException f) {
                        error("Value is inherently not a number!", currentLine);
                    }
                }
            }
            case "brk" -> throw new Break();
            case "cnt" -> throw new Continue();
            case "loop-end" -> error("Unexpected loop-end encountered", cmd.lineNum());
            case "ret" -> ret(parts);
            case "end" -> System.exit(0);
            default -> {
                // If it's not a built-in command, check if it's a function call
                if (variables.containsKey(cmd.keyword()) && variables.get(cmd.keyword()) instanceof Function func) {
                    callFunction(func, cmd);
                } else {
                    error(parts[0] + " did not match any keyword!", cmd.lineNum());
                }
            }
        }
    }

    private static void callFunction(Function func, Command cmd) {
        List<String> argList = new ArrayList<>();
        if (cmd.parts().length > 1) {
            String combinedArgs = cmd.parts()[1];
            if (cmd.parts().length > 2) {
                combinedArgs += " " + cmd.parts()[2];
            }
            String[] parsedArgs = combinedArgs.split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            argList.addAll(Arrays.asList(parsedArgs));
        }

        if (argList.size() != func.params().size()) {
            error("Function " + cmd.keyword() + " expects " + func.params().size() + " arguments, but got " + argList.size(), cmd.lineNum());
        }

        HashMap<String, Object> backups = new HashMap<>();
        List<String> toRemove = new ArrayList<>();

        for (int i = 0; i < func.params().size(); i++) {
            String paramName = func.params().get(i);
            Object argValue = parseValue(argList.get(i));

            if (variables.containsKey(paramName)) {
                backups.put(paramName, variables.get(paramName));
            } else {
                toRemove.add(paramName);
            }
            variables.put(paramName, argValue);
        }

        Object returnValue = null;
        try {
            runCode(func.commands());
        } catch (Return e) {
            returnValue = e.value;
        }

        for (String key : toRemove) {
            variables.remove(key);
        }
        for (Map.Entry<String, Object> entry : backups.entrySet()) {
            variables.put(entry.getKey(), entry.getValue());
        }
        variables.put("RETF", returnValue);
    }

    private static void ret(String[] parts) {
        if (parts.length < 2) {
            throw new Return(null);
        }
        Object val = parseValue(parts[1]);
        throw new Return(val);
    }

    private static Object parseValue(String val) {
        if (val == null) return null;

        if (variables.containsKey(val)) {
            return variables.get(val);
        }

        try {
            return parseNumber(val);
        } catch (NumberFormatException ignored) {}

        try {
            return Boolean.parse(val);
        } catch (Exception ignored) {}

        if (val.startsWith("\"") && val.endsWith("\"")) {
            return val.substring(1, val.length() - 1);
        }

        else error("Unknown Value", currentLine);
        return null;
    }

    private static Number parseNumber(String val) throws NumberFormatException {
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return Double.parseDouble(val);
        }
    }

    private static double extractNumber(String token) {
        try {
            return parseNumber(token).doubleValue();
        } catch (NumberFormatException ignored) {}

        if (variables.containsKey(token)) {
            Object obj = variables.get(token);
            if (obj instanceof Number num) {
                return num.doubleValue();
            }
            error("Variable " + token + " is not a number!" + token.getClass().getSimpleName(), currentLine);
        }
        
        error("Value " + token + " is not a valid number!", currentLine);
        return 0;
    }

    private static Number formatNumber(double result) {
        if (result == (long) result) return (long) result;
        return result;
    }

    private static void ascii(String[] parts) {
        Object val = variables.get(parts[1]);
        if (val instanceof String v && v.length() == 1) variables.put(parts[1], (long) v.charAt(0));
        else if (val instanceof Long) variables.put(parts[1], String.valueOf((char) ((Number) val).longValue()));
        else error("Value " + val + " is not a valid Character or Integer!", currentLine);
    }

    private static void cm(String[] parts) {
        if (parts.length < 3) {
            error("cm requires two arguments to compare", currentLine);
        }
        double n1, n2;
            try {
                    n1 = Boolean.parse(parts[1]).equals(Boolean.TRUE) ? 1 : 0;
            } catch (Exception e) {
                if (variables.get(parts[1]) instanceof Boolean) {
                    n1 = variables.get(parts[1]).equals(Boolean.TRUE) ? 1 : 0;
                } else {
                    n1 = extractNumber(parts[1]);
                }
            }
            try {
                n2 = Boolean.parse(parts[2]).equals(Boolean.TRUE) ? 1 : 0;
            } catch (Exception e) {
                if (variables.get(parts[2]) instanceof Boolean) {
                    n2 = variables.get(parts[2]).equals(Boolean.TRUE) ? 1 : 0;
                } else {
                    n2 = extractNumber(parts[2]);
                }
            }
        lastComparison = Double.compare(n1, n2);
    }

    private static void j(String[] parts) {
        if (parts.length < 3) {
            error("j requires a comparator and a command to execute", currentLine);
        }

        String comparator = parts[1];
        String targetCommandStr = parts[2];

        boolean conditionMet = switch (comparator) {
            case "l" -> lastComparison < 0;
            case "nl" -> !(lastComparison < 0);
            case "le" -> lastComparison <= 0;
            case "nle" -> !(lastComparison <= 0);
            case "e" -> lastComparison == 0;
            case "ne" -> !(lastComparison == 0);
            case "ge" -> lastComparison >= 0;
            case "nge" -> !(lastComparison >= 0);
            case "g" -> lastComparison > 0;
            case "ng" -> !(lastComparison > 0);
            default -> {
                error("Unknown comparator: " + comparator, currentLine);
                yield false;
            }
        };

        if (conditionMet) {
            String[] targetParts = targetCommandStr.split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 3);
            runCommand(new Command(currentLine, targetParts[0], targetParts));
        }
    }

    private static void db(String[] parts) {
        String val = parts[2].strip();
        try {
            variables.put(parts[1], parseNumber(val));
            return;
        } catch (NumberFormatException ignored) {}

        try {
            variables.put(parts[1], Boolean.parse(val));
            return;
        } catch (Exception ignored) {} 

        if (val.startsWith("\"") && val.endsWith("\"")) {
            variables.put(parts[1], val.substring(1, val.length() - 1));
            return;
        } else if (variables.containsKey(val)) {
            variables.put(parts[1], variables.get(val));
            return;
        }
        error("Invalid value type: [" + val + "]", currentLine);
    }

    private static void add(String[] parts) {
        Object current = variables.get(parts[1]);
        if (current instanceof String val) {
            String addend = variables.containsKey(parts[2]) ? String.valueOf(variables.get(parts[2])) : parts[2].replaceAll("^\"|\"$", "");
            variables.put(parts[1], val + addend);
        } else {
            double target = extractNumber(parts[1]);
            double addend = extractNumber(parts[2]);
            variables.put(parts[1], formatNumber(target + addend));
        }
    }

    private static void sub(String[] parts) {
        double subbed = extractNumber(parts[1]);
        double toSub = extractNumber(parts[2]);
        variables.put(parts[1], formatNumber(subbed - toSub));
    }

    private static void mul(String[] parts) {
        Object current = variables.get(parts[1]);
        if (current instanceof String value) {
            StringBuilder totalString = new StringBuilder();
            int multiplier = (int) extractNumber(parts[2]);
            for (int i = 0; i < multiplier; i++) {
                totalString.append(value);
            }
            variables.put(parts[1], totalString.toString());
        } else {
            double value = extractNumber(parts[1]);
            double multiplier = extractNumber(parts[2]);
            variables.put(parts[1], formatNumber(value * multiplier));
        }
    }

    private static void div(String[] parts) {
        double dividend = extractNumber(parts[1]);
        double divisor = extractNumber(parts[2]);
        variables.put(parts[1], formatNumber(dividend / divisor));
    }

    private static void pow(String[] parts) {
        double base = extractNumber(parts[1]);
        double power = extractNumber(parts[2]);
        variables.put(parts[1], formatNumber(Math.pow(base, power)));
    }

    private static void mod(String[] parts) {
        double val = extractNumber(parts[1]);
        double modBy = extractNumber(parts[2]);
        variables.put(parts[1], formatNumber(val % modBy));
    }

    private static void floor(String[] parts) {
        double val = extractNumber(parts[1]);
        variables.put(parts[1], formatNumber(Math.floor(val)));
    }

    private static void ceil(String[] parts) {
        double val = extractNumber(parts[1]);
        variables.put(parts[1], formatNumber(Math.ceil(val)));
    }

    private static void sy(String[] parts) {
        if (parts[2].equals("kernel")) {
            if (parts[1].equals("launch")) {
                for (Object e : kernel) {
                    if ("\\n".equals(e)) {
                        System.out.print("\n");
                    } else {
                        System.out.print(e);
                    }
                }
            } else if (parts[1].equals("retrieve")) {
                kernel.add(new Scanner(System.in).nextLine());
            }
        }
        
    }

    private static void mv(String[] parts) {
        Object part1;
        try {
            part1 = parseNumber(parts[1]);
        } catch (NumberFormatException l) {
            if (parts[1].equals("TRUE") || parts[1].equals("FALSE")) {
                part1 = parts[1].equals("TRUE") ? Boolean.TRUE : Boolean.FALSE;
            } else if (parts[1].length() >= 2 && parts[1].startsWith("\"") && parts[1].endsWith("\"")) {
                part1 = parts[1].substring(1, parts[1].length() - 1);
            } else if (variables.containsKey(parts[1])) {
                part1 = variables.get(parts[1]);
            } else {
                error("Variable " + parts[1] + " could not be found!", currentLine);
                return;
            }
        }

        if (parts[2].equals("kernel")) {
            kernel.add(part1);
        } else if (variables.containsKey(parts[2])) {
            variables.put(parts[2], part1);
        } else {
            error("Target location [" + parts[2] + "] not found!", currentLine);
        }
    }

    private static void cl(String[] parts) {
        if (parts[1].equals("kernel")) {
            kernel.clear();
        } else if (parts[1].equals("*kernel")) {
            kernel = null;
        } else if (parts[1].startsWith("*") && variables.containsKey(parts[1].substring(1))) {
            variables.remove(parts[1].substring(1));
        } else if (variables.containsKey(parts[1])) {
            Object obj = variables.get(parts[1]);
            if (obj instanceof Number) variables.put(parts[1], 0);
            else if (obj instanceof Boolean) variables.put(parts[1], false);
            else if (obj instanceof String) variables.put(parts[1], "");
            else error("Variable " + parts[1] + " is not an allowed type! [" + parts[1].getClass().getSimpleName() + "]", currentLine);
        } else {
            error("Cannot find variable " + parts[1], currentLine);
        }
    }

    private static void error(String error, int lineNum) {
        System.err.println("BLAP Program Exception on Line " + lineNum + ": " + error);
        System.exit(1);
    }
}