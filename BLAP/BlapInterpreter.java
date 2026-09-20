import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
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

    private final class Cell {
        Object value;

        Cell(Object value) {
            this.value = value;
        }
    }

     final HashMap<String, Cell> variables = new HashMap<>();
     ArrayList<Object> kernel = new ArrayList<>();
     int currentLine = 0;
     int lastComparison = 0;
     private BlapInterpreter caller;
     private final Scanner scanner = new Scanner(System.in);

    private enum Boolean {
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

    private class Break extends RuntimeException {}
    private class Continue extends RuntimeException {}
    private class Return extends RuntimeException {
        final Object value;
        Return(Object value) {
            this.value = value;
        }
    }

    private final record Command(int lineNum, String keyword, String[] parts) {}
    private final record Function(BlapInterpreter owner, boolean isBlap, List<String> params, List<Command> commands, List<Command> alwaysRunCommands) {}

    void main(String[] args) {
        if (args.length != 1) error("Usage: java BlapInterpreter <BLAP file>", 0);
        start(args[0], this);
    }

    void start(String filePath, BlapInterpreter caller) {
        this.caller = caller;
        if (filePath.isEmpty() || !filePath.endsWith(".blap")) {
            error("Usage: java BlapInterpreter <BLAP file>", 0);
            return;
        }
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


     private void organizeCode(String code) {
        variables.put("NLN", new Cell("\n"));
        variables.put("RETF", new Cell(null));
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

            if (parts[0].equals("fn") && parts.length > 1) {
                String[] funcSig = parts[1].split(":");
                String funcName = funcSig[0];
                
                List<String> params = new ArrayList<>();
                if(parts[1].contains(":")) for (int p = 1; p < funcSig.length; p++) {
                    params.add(funcSig[p]);
                }
                
                List<Command> funcBody = new ArrayList<>();
                List<Command> alwaysRunCommands = new ArrayList<>();
                boolean foundEnd = false;
                
                while (st.hasMoreTokens()) {
                    line++;
                    String fStatement = st.nextToken().trim();
                    if (fStatement.isEmpty() || fStatement.startsWith("-#")) continue;
                    
                    String[] fParts = fStatement.split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 3);

                    if (fParts[0].equals("end") && fParts.length > 1 && fParts[1].equals(funcName)) {
                        foundEnd = true;
                        break;
                    }

                    if (fParts[0].equals("alw")) alwaysRunCommands.add(new Command(line, fParts[1], Arrays.copyOfRange(fParts, 1, fParts.length)));
                    
                    else funcBody.add(new Command(line, fParts[0], fParts));
                }
                
                if (!foundEnd) {
                    error("Unterminated function: " + funcName + " (missing 'end " + funcName + ";')", line);
                }
                
                caller.variables.put(funcName, new Cell(new Function(this, true, params, funcBody, alwaysRunCommands)));
                continue;
            }

            else if (parts[0].equals("javafn") && parts.length > 1) {
                parts = statement.split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 4);
                String[] funcSig = parts[1].split(":");
                List<String> params = new ArrayList<>();
                
                if(parts[1].contains(":")) for (int p = 1; p < funcSig.length; p++) {
                    params.add(funcSig[p]);
                }
                caller.variables.put(
                    funcSig[0],
                    new Cell(new Function(
                        this,
                        false,
                        params,
                        List.of(new Command(line, parts[2], null), new Command(line, parts[3], null)),
                        null
                    ))
                );
                continue;
            }

            commands.add(new Command(line, parts[0], parts));
        }

        runCode(commands);
    }

     private void runCode(List<Command> commands) {
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
                    if (val == (long) val) {
                        variables.put(varName, new Cell((long) val));
                    } else {
                        variables.put(varName, new Cell(val));
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

     private void runCommand(Command cmd) {
        if (!kernel.isEmpty()) variables.put("KRNL_GTLTST", new Cell(kernel.getLast()));
        String[] parts = cmd.parts();
        switch (cmd.keyword()) {
            case "use" -> new BlapInterpreter().start(parts[1], this);
            case "alw" -> error("alw can only be used in a loop or function!", cmd.lineNum());
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
                    variables.put(parts[1], new Cell(Long.parseLong(String.valueOf(variables.get(parts[1]).value))));
                } catch (NumberFormatException e) {
                    try {
                        variables.put(parts[1], new Cell(Double.parseDouble(String.valueOf(variables.get(parts[1]).value))));
                    } catch (NumberFormatException f) {
                        error("Value is inherently not a number!", currentLine);
                    }
                }
            } 
            case "mkstr" -> variables.put(parts[1], new Cell(String.valueOf(variables.get(parts[1]).value)));
            case "brk" -> throw new Break();
            case "cnt" -> throw new Continue();
            case "loop-end" -> error("Unexpected loop-end encountered", cmd.lineNum());
            case "ret" -> ret(parts);
            case "end" -> System.exit(0);
            default -> {
                // If it's not a built-in command, check if it's a function call
                if (variables.containsKey(cmd.keyword()) && variables.get(cmd.keyword()).value instanceof Function func) {
                    func.owner().callFunction(func, cmd);
                } else {
                    error(parts[0] + " did not match any keyword!", cmd.lineNum());
                }
            }
        }
    }

     void callFunction(Function func, Command cmd) {
        if (func.isBlap()) {
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
                    backups.put(paramName, variables.get(paramName).value);
                } else {
                    toRemove.add(paramName);
                }
                variables.put(paramName, new Cell(argValue));
            }

            Object returnValue = null;
            try {
                runCode(func.commands());
            } catch (Return e) {
                returnValue = e.value;
            } finally {
                for (Command c : func.alwaysRunCommands()) {
                    runCommand(c);
                }
            }

            for (String key : toRemove) {
                variables.remove(key);
            }
            for (Map.Entry<String, Object> entry : backups.entrySet()) {
                variables.put(entry.getKey(), new Cell(entry.getValue()));
            }
            caller.variables.get("RETF").value = returnValue;
        }
        else {
            String className = func.commands().get(0).keyword();
            String[] methodChain = func.commands().get(1).keyword().split("\\.");

            try {
                List<Object> args = new ArrayList<>();
                if (cmd.parts().length > 1) {
                    String result = cmd.parts()[1];
                    if (cmd.parts().length > 2) {
                        result += " " + cmd.parts()[2];
                    }
                    String[] rawArgs = result.split("\\s+");
                    for (String arg : rawArgs) {
                        args.add(parseValue(arg));
                    }
                }

                Class<?> currentClass = Class.forName(className);
                Object currentObj = null;

                for (int i = 0; i < methodChain.length; i++) {
                    String methodName = methodChain[i];
                    boolean isLastMethod = (i == methodChain.length - 1);

                    java.lang.reflect.Method targetMethod = null;
                    Object[] invokeArgs = null;

                    if (isLastMethod && !args.isEmpty()) {
                        for (java.lang.reflect.Method method : currentClass.getMethods()) {
                            if (method.getName().equals(methodName) && method.getParameterCount() == args.size()) {
                                Class<?>[] pTypes = method.getParameterTypes();
                                boolean match = true;
                                Object[] matchedArgs = new Object[args.size()];

                                for (int j = 0; j < args.size(); j++) {
                                    Object arg = args.get(j);
                                    Class<?> pType = pTypes[j];

                                    switch (arg) {
                                        case Double d -> {
                                            if (pType == double.class || pType == Double.class) matchedArgs[j] = d;
                                            else if (pType == float.class || pType == Float.class) matchedArgs[j] = d.floatValue();
                                            else if (pType.isAssignableFrom(Double.class)) matchedArgs[j] = d;
                                            else { match = false; break; }
                                        }
                                        case Long l -> {
                                            if (pType == long.class || pType == Long.class) matchedArgs[j] = l;
                                            else if (pType == int.class || pType == Integer.class) matchedArgs[j] = l.intValue();
                                            else if (pType == double.class || pType == Double.class) matchedArgs[j] = l.doubleValue();
                                            else if (pType == float.class || pType == Float.class) matchedArgs[j] = l.floatValue();
                                            else if (pType.isAssignableFrom(Long.class)) matchedArgs[j] = l;
                                            else { match = false; break; }
                                        }
                                        case Boolean b -> {
                                            if (pType == boolean.class || pType == java.lang.Boolean.class) matchedArgs[j] = (b == Boolean.TRUE);
                                            else if (pType.isAssignableFrom(java.lang.Boolean.class)) matchedArgs[j] = (b == Boolean.TRUE);
                                            else { match = false; break; }
                                        }
                                        default -> {
                                            if (pType.isAssignableFrom(arg.getClass())) matchedArgs[j] = arg;
                                            else { match = false; break; }
                                        }
                                    }
                                }

                                if (match) {
                                    targetMethod = method;
                                    invokeArgs = matchedArgs;
                                    break;
                                }
                            }
                        }
                        
                        if (targetMethod == null) {
                            throw new NoSuchMethodException("No compatible method " + methodName + " found for the provided arguments.");
                        }
                    } else {
                        targetMethod = currentClass.getMethod(methodName);
                        invokeArgs = new Object[0];
                    }

                    targetMethod.setAccessible(true);

                    if (currentObj == null) {
                        if (java.lang.reflect.Modifier.isStatic(targetMethod.getModifiers())) {
                            currentObj = targetMethod.invoke(null, invokeArgs);
                        } else {
                            Object instance = currentClass.getDeclaredConstructor().newInstance();
                            currentObj = targetMethod.invoke(instance, invokeArgs);
                        }
                    } else {
                        currentObj = targetMethod.invoke(currentObj, invokeArgs);
                    }
                    if (currentObj != null && !isLastMethod) {
                        currentClass = currentObj.getClass();
                    }
                }
                caller.variables.get("RETF").value = currentObj;

            } catch (ClassNotFoundException | IllegalAccessException | IllegalArgumentException | InstantiationException | NoSuchMethodException | InvocationTargetException e) {
                error("Failed to load native method: " + e.getMessage(), currentLine);
            }
        }
    }

     private void ret(String[] parts) {
        if (parts.length < 2) {
            throw new Return(null);
        }
        Object val = parseValue(parts[1]);
        throw new Return(val);
    }

    private boolean isPointer(String arg) {
        return arg.startsWith("*");
    }

    private String getCleanName(String arg) {
        return arg.startsWith("*") ? arg.substring(1) : arg;
    }

     private Object parseValue(String val) {
        if (val == null) return null;

        if (variables.containsKey(val)) {
            return variables.get(val).value;
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

     private Number parseNumber(String val) throws NumberFormatException {
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return Double.parseDouble(val);
        }
    }

     private double extractNumber(String token) {
        try {
            return parseNumber(token).doubleValue();
        } catch (NumberFormatException ignored) {}

        if (variables.containsKey(token)) {
            Object obj = variables.get(token).value;
            if (obj instanceof Number num) {
                return num.doubleValue();
            }
            error("Variable " + token + " is not a number!" + token.getClass().getSimpleName(), currentLine);
        }
        
        error("Value " + token + " is not a valid number!", currentLine);
        return 0;
    }

     private Number formatNumber(double result) {
        if (result == (long) result) return (long) result;
        return result;
    }

     private void ascii(String[] parts) {
        Object val = variables.get(parts[1]).value;
        if (val instanceof String v && v.length() == 1) variables.put(parts[1], new Cell((long) v.charAt(0)));
        else if (val instanceof Long) variables.put(parts[1], new Cell(String.valueOf((char) ((Number) val).longValue())));
        else error("Value " + val + " is not a valid Character or Integer!", currentLine);
    }

     private void cm(String[] parts) {
        if (parts.length < 3) {
            error("cm requires two arguments to compare", currentLine);
        }
        double n1, n2;
            try {
                    n1 = Boolean.parse(parts[1]).equals(Boolean.TRUE) ? 1 : 0;
            } catch (Exception e) {
                if (variables.get(parts[1]).value instanceof Boolean) {
                    n1 = variables.get(parts[1]).value.equals(Boolean.TRUE) ? 1 : 0;
                } else {
                    n1 = extractNumber(parts[1]);
                }
            }
            try {
                n2 = Boolean.parse(parts[2]).equals(Boolean.TRUE) ? 1 : 0;
            } catch (Exception e) {
                if (variables.get(parts[2]).value instanceof Boolean) {
                    n2 = variables.get(parts[2]).value.equals(Boolean.TRUE) ? 1 : 0;
                } else {
                    n2 = extractNumber(parts[2]);
                }
            }
        lastComparison = Double.compare(n1, n2);
    }

     private void j(String[] parts) {
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
            case "ne" -> lastComparison != 0;
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

     private void db(String[] parts) {
        String val = parts[2].strip();
        if (isPointer(val)) {
            variables.put(parts[1], variables.get(getCleanName(val)));
            return;
        }
        try {
            variables.put(parts[1], new Cell(parseNumber(val)));
            return;
        } catch (NumberFormatException ignored) {}
        try {
            variables.put(parts[1], new Cell(Boolean.parse(val)));
            return;
        } catch (Exception ignored) {} 

        if (val.startsWith("\"") && val.endsWith("\"")) {
            variables.put(parts[1], new Cell(val.substring(1, val.length() - 1)));
            return;
        } else if (variables.containsKey(val)) {
            variables.put(parts[1], new Cell(variables.get(val).value));
            return;
        }
        error("Invalid value type: [" + val + "]", currentLine);
    }

     private void add(String[] parts) {
        if (variables.get(parts[1]).value instanceof String val) {
            String addend = variables.containsKey(parts[2]) ? String.valueOf(variables.get(parts[2]).value) : parts[2].replaceAll("^\"|\"$", "");
            variables.get(parts[1]).value = val + addend;
        } else {
            double target = extractNumber(parts[1]);
            double addend = extractNumber(parts[2]);
            variables.get(parts[1]).value = formatNumber(target + addend);
        }
    }

     private void sub(String[] parts) {
        double subbed = extractNumber(parts[1]);
        double toSub = extractNumber(parts[2]);
        variables.get(parts[1]).value =  formatNumber(subbed - toSub);
    }

     private void mul(String[] parts) {
        Object current = variables.get(parts[1]).value;
        if (current instanceof String value) {
            StringBuilder totalString = new StringBuilder();
            int multiplier = (int) extractNumber(parts[2]);
            for (int i = 0; i < multiplier; i++) {
                totalString.append(value);
            }
            variables.get(parts[1]).value = totalString.toString();
        } else {
            double value = extractNumber(parts[1]);
            double multiplier = extractNumber(parts[2]);
            variables.get(parts[1]).value = formatNumber(value * multiplier);
        }
    }

     private void div(String[] parts) {
        double dividend = extractNumber(parts[1]);
        double divisor = extractNumber(parts[2]);
        variables.get(parts[1]).value = formatNumber(dividend / divisor);
    }

     private void pow(String[] parts) {
        double base = extractNumber(parts[1]);
        double power = extractNumber(parts[2]);
        variables.get(parts[1]).value = formatNumber(Math.pow(base, power));
    }

     private void mod(String[] parts) {
        double val = extractNumber(parts[1]);
        double modBy = extractNumber(parts[2]);
        variables.get(parts[1]).value = formatNumber(val % modBy);
    }

     private void floor(String[] parts) {
        double val = extractNumber(parts[1]);
        variables.get(parts[1]).value = formatNumber(Math.floor(val));
    }

     private void ceil(String[] parts) {
        double val = extractNumber(parts[1]);
        variables.get(parts[1]).value = formatNumber(Math.ceil(val));
    }

     private void sy(String[] parts) {
        if (parts[2].equals("kernel")) {
            if (kernel == null) error("Kernel is destroyed!", currentLine); 
            if (parts[1].equals("launch")) {
                for (Object e : kernel) {
                    if ("\\n".equals(e)) {
                        System.out.print("\n");
                    } else {
                        System.out.print(e);
                    }
                }
            } else if (parts[1].equals("retrieve")) {
                if (scanner.hasNextLine()) kernel.add(scanner.nextLine());
            }
        }
        
    }

     private void mv(String[] parts) {
        Object part1 = getCleanName(parts[1]);
        try {
            part1 = parseNumber(((String) part1));
        } catch (NumberFormatException l) {
            if (((String) part1).equals("TRUE") || ((String) part1).equals("FALSE")) {
                part1 = ((String) part1).equals("TRUE") ? Boolean.TRUE : Boolean.FALSE;
            } else if (((String) part1).length() >= 2 && ((String) part1).startsWith("\"") && ((String) part1).endsWith("\"")) {
                part1 = ((String) part1).substring(1, parts[1].length() - 1);
            } else if (variables.containsKey((String) part1)) {
                part1 = variables.get((String) part1).value;
            } else {
                error("Variable " + part1 + " could not be found!", currentLine);
                return;
            }
        }

        if (parts[2].equals("kernel")) {
            if (kernel == null) error("Kernel is destroyed!", currentLine);
            kernel.add(part1);
            if (isPointer(parts[1])) variables.remove(getCleanName(parts[1]));
        } else if (variables.containsKey(parts[2])) {
            variables.put(parts[2], new Cell(part1));
            if (isPointer(parts[1])) variables.remove(getCleanName(parts[1]));
        } else {
            error("Target location [" + parts[2] + "] not found!", currentLine);
        }
    }

     private void cl(String[] parts) {
        if (parts[1].equals("kernel")) {
            kernel.clear();
        } else if (parts[1].equals("*kernel")) {
            kernel = null;
        } else if (isPointer(parts[1])) {
            variables.remove(getCleanName(parts[1]));
            kernel.remove(getCleanName(parts[1]));
        } else if (variables.containsKey(parts[1])) {
            Cell obj = variables.get(parts[1]);
            if (obj.value instanceof Number) obj.value = 0;
            else if (obj.value instanceof Boolean) obj.value = Boolean.FALSE;
            else if (obj.value instanceof String) obj.value = "";
            else error("Variable " + parts[1] + " is not an allowed type! [" + parts[1].getClass().getSimpleName() + "]", currentLine);
        } else {
            error("Cannot find variable " + parts[1], currentLine);
        }
    }

     private void error(String error, int lineNum) {
        System.err.println("BLAP Program Exception on Line " + lineNum + ": " + error);
        System.exit(1);
    }
}
