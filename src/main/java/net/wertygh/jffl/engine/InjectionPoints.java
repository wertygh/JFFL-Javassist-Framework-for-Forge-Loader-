package net.wertygh.jffl.engine;

import javassist.*;
import javassist.bytecode.*;
import javassist.expr.*;
import net.wertygh.jffl.api.annotation.At;
import net.wertygh.jffl.api.annotation.Slice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InjectionPoints {
    private static Logger LOGGER = LoggerFactory.getLogger(InjectionPoints.class);
    
    public static int[] resolveSliceRange(CtMethod method, Slice slice) {
        if (slice == null) return new int[]{-1, Integer.MAX_VALUE};
        At from = slice.from();
        At to = slice.to();
        int fromLine = -1;
        int toLine = Integer.MAX_VALUE;
        if (from.value() == At.Value.LINE) {
            fromLine = from.line();
        } else if (from.value() != At.Value.HEAD) {
            fromLine = findAtPosition(method, from);
        }
        if (to.value() == At.Value.LINE) {
            toLine = to.line();
        } else if (to.value() != At.Value.TAIL) {
            toLine = findAtPosition(method, to);
        }
        return new int[]{fromLine, toLine};
    }

    private static int findAtPosition(CtMethod method, At at) {
        if (at.value() == At.Value.HEAD) return -1;
        if (at.value() == At.Value.TAIL) return Integer.MAX_VALUE;
        if (at.value() == At.Value.LINE) return at.line();
        if (at.value() == At.Value.INVOKE || at.value() == At.Value.FIELD) {
            int[] result = {-1};
            int wantOrdinal = at.ordinal();
            String target = at.target();
            int[] seen = {0};
            try {
                if (at.value() == At.Value.INVOKE) {
                    method.instrument(new ExprEditor() {
                        @Override
                        public void edit(MethodCall mc) {
                            if (!matchesInvokeTarget(mc, target)) return;
                            int idx = seen[0]++;
                            if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                            if (result[0] < 0) result[0] = mc.getLineNumber();
                        }
                    });
                } else {
                    method.instrument(new ExprEditor() {
                        @Override
                        public void edit(FieldAccess fa) {
                            if (!target.isEmpty() && !target.equals(fa.getFieldName())) return;
                            int idx = seen[0]++;
                            if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                            if (result[0] < 0) result[0] = fa.getLineNumber();
                        }
                    });
                }
            } catch (CannotCompileException e) {
                LOGGER.warn("在解析@At({})位置时, 无法插桩方法{}：{}",
                        method.getName(), at.value(), e.getMessage());
            }
            return result[0];
        }
        return -1;
    }

    public static void apply(CtMethod method, At at, String src, Slice slice) throws CannotCompileException {
        int[] range = resolveSliceRange(method, slice);
        apply(method, at, src, range[0], range[1]);
    }

    public static void applyRedirect(CtMethod method, At at, String body, Slice slice) throws CannotCompileException {
        int[] range = resolveSliceRange(method, slice);
        applyRedirect(method, at, body, range[0], range[1]);
    }

    public static void apply(CtMethod method, At at, String src) throws CannotCompileException {
        apply(method, at, src, -1, Integer.MAX_VALUE);
    }

    public static void applyRedirect(CtMethod method, At at, String body) throws CannotCompileException {
        applyRedirect(method, at, body, -1, Integer.MAX_VALUE);
    }

    public static void applyRedirect(CtMethod method, At at, String body, int sliceFrom, int sliceTo) throws CannotCompileException {
        if (at.value() != At.Value.INVOKE && at.value() != At.Value.FIELD) {
            throw new CannotCompileException("@Redirect仅支持@At(INVOKE)或@At(FIELD)");
        }
        int wantOrdinal = at.ordinal();
        String target = at.target();
        int[] seen = {0};
        int[] matched = {0};
        if (at.value() == At.Value.INVOKE) {
            method.instrument(new ExprEditor() {
                @Override public void edit(MethodCall mc) throws CannotCompileException {
                    if (!isInSlice(mc.getLineNumber(), sliceFrom, sliceTo)) return;
                    if (!matchesInvokeTarget(mc, target)) return;
                    int idx = seen[0]++;
                    if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                    matched[0]++;
                    mc.replace(body);
                }
            });
        } else {
            method.instrument(new ExprEditor() {
                @Override public void edit(FieldAccess fa) throws CannotCompileException {
                    if (!isInSlice(fa.getLineNumber(), sliceFrom, sliceTo)) return;
                    if (!target.isEmpty() && !target.equals(fa.getFieldName())) return;
                    int idx = seen[0]++;
                    if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                    matched[0]++;
                    fa.replace(body);
                }
            });
        }
        if (matched[0] == 0) {
            LOGGER.warn("@Redirect未命中{}.{} at={} target={} ordinal={}",
                    method.getDeclaringClass().getName(), method.getName(), at.value(), target, wantOrdinal);
        }
    }

    public static void apply(CtMethod method, At at, String src, int sliceFrom, int sliceTo) throws CannotCompileException {
        boolean matched = switch (at.value()) {
            case HEAD -> {
                method.insertBefore(src);
                yield true;
            }
            case RETURN -> {
                method.insertAfter(src, false);
                yield true;
            }
            case TAIL -> {
                insertBeforeLastReturn(method, src);
                yield true;
            }
            case LINE -> {
                method.insertAt(at.line(), src);
                yield true;
            }
            case INVOKE -> instrumentInvoke(method, at, src, sliceFrom, sliceTo);
            case FIELD -> instrumentField(method, at, src, sliceFrom, sliceTo);
            case CONSTANT -> {
                instrumentConstant(method, at, src, sliceFrom, sliceTo);
                yield true;
            }
        };
        if (!matched) {
            LOGGER.warn("@At({})未命中{}.{} target={} ordinal={}",
                    at.value(), method.getDeclaringClass().getName(), method.getName(), at.target(), at.ordinal());
        }
    }

    private static boolean isInSlice(int lineNumber, int sliceFrom, int sliceTo) {
        if (sliceFrom < 0 && sliceTo == Integer.MAX_VALUE) return true;
        if (lineNumber <= 0) return true;
        return lineNumber >= sliceFrom && lineNumber <= sliceTo;
    }

    private static boolean instrumentInvoke(CtMethod method, At at, String src, int sliceFrom, int sliceTo) throws CannotCompileException {
        int wantOrdinal = at.ordinal();
        String target = at.target();
        boolean shiftAfter = at.shift() == At.Shift.AFTER;
        int[] seen = {0};
        int[] matched = {0};
        method.instrument(new ExprEditor() {
            @Override public void edit(MethodCall mc) throws CannotCompileException {
                if (!isInSlice(mc.getLineNumber(), sliceFrom, sliceTo)) return;
                if (!matchesInvokeTarget(mc, target)) return;
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                matched[0]++;
                String body = shiftAfter
                        ? "{ $_ = $proceed($$); " + src + " }"
                        : "{ " + src + " $_ = $proceed($$); }";
                mc.replace(body);
            }
        });
        return matched[0] > 0;
    }

    static boolean matchesInvokeTarget(MethodCall mc, String target) {
        if (target == null || target.isEmpty()) return true;
        if (target.equals(mc.getMethodName())) return true;
        String full = mc.getClassName() + "." + mc.getMethodName() + mc.getSignature();
        if (target.equals(full)) return true;
        String shortForm = mc.getMethodName() + mc.getSignature();
        return target.equals(shortForm);
    }

    private static boolean instrumentField(CtMethod method, At at, String src, int sliceFrom, int sliceTo) throws CannotCompileException {
        int wantOrdinal = at.ordinal();
        String target = at.target();
        boolean shiftAfter = at.shift() == At.Shift.AFTER;
        int[] seen = {0};
        int[] matched = {0};
        method.instrument(new ExprEditor() {
            @Override public void edit(FieldAccess fa) throws CannotCompileException {
                if (!isInSlice(fa.getLineNumber(), sliceFrom, sliceTo)) return;
                if (!target.isEmpty() && !target.equals(fa.getFieldName())) return;
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                matched[0]++;
                String proceed = fa.isReader() ? "$_ = $proceed($$);" : "$proceed($$);";
                String body = shiftAfter
                        ? "{ " + proceed + " " + src + " }"
                        : "{ " + src + " " + proceed + " }";
                fa.replace(body);
            }
        });
        return matched[0] > 0;
    }

    private static void instrumentConstant(CtMethod method, At at, String src, int sliceFrom, int sliceTo) throws CannotCompileException {
        if (at.intValue() != Integer.MIN_VALUE) {
            replaceIntConstant(method, at.intValue(), at.ordinal(), src, sliceFrom, sliceTo);
        } else if (!" ".equals(at.stringValue())) {
            replaceStringConstant(method, at.stringValue(), at.ordinal(), src, sliceFrom, sliceTo);
        } else {
            double dv = at.doubleValue();
            if (!Double.isNaN(dv)) {
                replaceNumericConstant(method, dv, at.ordinal(), src, sliceFrom, sliceTo);
            } else {
                LOGGER.warn("@At(CONSTANT)没有intValue/stringValue/doubleValue选择器, 在 {}.{}上没有任何操作", method.getDeclaringClass().getName(), method.getName());
            }
        }
    }

    private static void insertBeforeLastReturn(CtMethod method, String src) throws CannotCompileException {
        MethodInfo mi = method.getMethodInfo();
        CodeAttribute ca = mi.getCodeAttribute();
        if (ca == null) return;
        try {
            CodeIterator it = ca.iterator();
            int lastReturnPos = -1;
            while (it.hasNext()) {
                int pos = it.next();
                int op = it.byteAt(pos);
                if (op >= Opcode.IRETURN && op <= Opcode.RETURN) lastReturnPos = pos;
            }
            if (lastReturnPos < 0) {
                method.insertAfter(src, false);
                return;
            }
            int line = mi.getLineNumber(lastReturnPos);
            if (line > 0) {
                method.insertAt(line, src);
            } else {
                method.insertAfter(src, false);
            }
        } catch (Exception e) {throw new CannotCompileException(e);}
    }

    private static void replaceIntConstant(CtMethod method, int wanted, int wantOrdinal, String newExpr, int sliceFrom, int sliceTo) throws CannotCompileException {
        MethodInfo mi = method.getMethodInfo();
        CodeAttribute ca = mi.getCodeAttribute();
        if (ca == null) return;
        ConstPool cp = mi.getConstPool();
        int newValue = parseInt(newExpr, wanted);
        int nextTarget = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            CodeIterator it = ca.iterator();
            int currentOccurrence = 0;
            try {
                while (it.hasNext()) {
                    int pos = it.next();
                    int op = it.byteAt(pos);
                    int line = mi.getLineNumber(pos);
                    if (!isInSlice(line, sliceFrom, sliceTo)) continue;          
                    Integer loaded = readIntConstant(it, cp, pos, op);
                    if (loaded == null || loaded != wanted) continue;
                    if (currentOccurrence < nextTarget) {currentOccurrence++; continue;}
                    if (wantOrdinal >= 0 && currentOccurrence != wantOrdinal) {
                        currentOccurrence++;
                        continue;
                    }
                    rewriteIntInstruction(it, cp, pos, op, newValue);
                    nextTarget = currentOccurrence + 1;
                    changed = (wantOrdinal < 0);
                    break;
                }
            } catch (BadBytecode e) {throw new CannotCompileException(e);}
            if (wantOrdinal >= 0) break;
        }
    }

    private static void rewriteIntInstruction(CodeIterator it, ConstPool cp, int pos, int op, int newValue) throws BadBytecode {
        int origSize = instructionSize(op);
        if (newValue >= -1 && newValue <= 5) {
            int targetOp = Opcode.ICONST_0 + newValue;
            if (newValue == -1) targetOp = Opcode.ICONST_M1;
            writeWithNops(it, pos, origSize, new byte[]{(byte) targetOp});
        } else if (newValue >= Byte.MIN_VALUE && newValue <= Byte.MAX_VALUE) {
            writeWithNops(it, pos, origSize, new byte[]{(byte) Opcode.BIPUSH, (byte) newValue});
        } else if (newValue >= Short.MIN_VALUE && newValue <= Short.MAX_VALUE) {
            writeWithNops(it, pos, origSize, new byte[]{
                (byte) Opcode.SIPUSH, (byte) (newValue >> 8), (byte) newValue
            });
        } else {
            int cpIdx = cp.addIntegerInfo(newValue);
            writeWithNops(it, pos, origSize, new byte[]{
                (byte) Opcode.LDC_W, (byte) (cpIdx >> 8), (byte) cpIdx
            });
        }
    }

    private static void writeWithNops(CodeIterator it, int pos, int origSize, byte[] replacement) throws BadBytecode {
        if (replacement.length <= origSize) {
            for (int i=0;i<replacement.length;i++) {
                it.writeByte(replacement[i] & 0xFF, pos + i);
            }
            for (int i=replacement.length;i<origSize;i++) {
                it.writeByte(Opcode.NOP, pos + i);
            }
        } else {
            int extra = replacement.length - origSize;
            it.insertGap(pos, extra);
            for (int i=0;i<replacement.length;i++) {
                it.writeByte(replacement[i] & 0xFF, pos + i);
            }
        }
    }

    private static int instructionSize(int op) {
        return switch (op) {
            case Opcode.ICONST_M1, Opcode.ICONST_0, Opcode.ICONST_1,
                 Opcode.ICONST_2, Opcode.ICONST_3, Opcode.ICONST_4,
                 Opcode.ICONST_5,
                 Opcode.FCONST_0, Opcode.FCONST_1, Opcode.FCONST_2,
                 Opcode.DCONST_0, Opcode.DCONST_1,
                 Opcode.LCONST_0, Opcode.LCONST_1
                    -> 1;
            case Opcode.BIPUSH, Opcode.LDC -> 2;
            case Opcode.SIPUSH, Opcode.LDC_W, Opcode.LDC2_W -> 3;
            default -> 1;
        };
    }

    private static void replaceNumericConstant(CtMethod method, double wanted, int wantOrdinal, String newExpr, int sliceFrom, int sliceTo) throws CannotCompileException {
        MethodInfo mi = method.getMethodInfo();
        CodeAttribute ca = mi.getCodeAttribute();
        if (ca == null) return;
        ConstPool cp = mi.getConstPool();
        int seen = 0;
        try {
            CodeIterator it = ca.iterator();
            while (it.hasNext()) {
                int pos = it.next();
                int op = it.byteAt(pos);
                int line = mi.getLineNumber(pos);
                if (!isInSlice(line, sliceFrom, sliceTo)) continue;
                NumericConstant loaded = readNumericConstant(it, cp, pos, op);
                if (loaded == null) continue;
                if (!matchesNumeric(loaded.value, wanted)) continue;
                if (wantOrdinal >= 0 && seen++ != wantOrdinal) continue;
                rewriteNumericInstruction(it, cp, pos, op, loaded.type, newExpr);
            }
        } catch (BadBytecode e) {throw new CannotCompileException(e);}
    }

    private enum NumType { FLOAT, DOUBLE, LONG }

    private static class NumericConstant {
        public double value;
        public NumType type;

        NumericConstant(double value, NumType type) {
            this.value = value;
            this.type = type;
        }
    }

    private static NumericConstant readNumericConstant(CodeIterator it, ConstPool cp, int pos, int op) {
        try {
            return switch (op) {
                case Opcode.FCONST_0 -> new NumericConstant(0.0, NumType.FLOAT);
                case Opcode.FCONST_1 -> new NumericConstant(1.0, NumType.FLOAT);
                case Opcode.FCONST_2 -> new NumericConstant(2.0, NumType.FLOAT);
                case Opcode.DCONST_0 -> new NumericConstant(0.0, NumType.DOUBLE);
                case Opcode.DCONST_1 -> new NumericConstant(1.0, NumType.DOUBLE);
                case Opcode.LCONST_0 -> new NumericConstant(0.0, NumType.LONG);
                case Opcode.LCONST_1 -> new NumericConstant(1.0, NumType.LONG);
                case Opcode.LDC -> {
                    int idx = it.byteAt(pos + 1);
                    yield numFromCp(cp, idx);
                }
                case Opcode.LDC_W -> {
                    int idx = it.u16bitAt(pos + 1);
                    yield numFromCp(cp, idx);
                }
                case Opcode.LDC2_W -> {
                    int idx = it.u16bitAt(pos + 1);
                    yield num2FromCp(cp, idx);
                }
                default -> null;
            };
        } catch (Exception e) {return null;}
    }

    private static NumericConstant numFromCp(ConstPool cp, int idx) {
        int tag = cp.getTag(idx);
        if (tag == ConstPool.CONST_Float) {
            return new NumericConstant(cp.getFloatInfo(idx), NumType.FLOAT);
        }
        return null;
    }

    private static NumericConstant num2FromCp(ConstPool cp, int idx) {
        int tag = cp.getTag(idx);
        if (tag == ConstPool.CONST_Double) {
            return new NumericConstant(cp.getDoubleInfo(idx), NumType.DOUBLE);
        }
        if (tag == ConstPool.CONST_Long) {
            return new NumericConstant((double) cp.getLongInfo(idx), NumType.LONG);
        }
        return null;
    }

    private static boolean matchesNumeric(double a, double b) {
        if (Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b)) return true;
        return Math.abs(a - b) < 1e-6 * Math.max(1.0, Math.abs(a));
    }

    private static void rewriteNumericInstruction(CodeIterator it, ConstPool cp, int pos, int op, NumType type, String newExpr) throws BadBytecode {
        int origSize = instructionSize(op);
        switch (type) {
            case FLOAT -> {
                float nv = parseFloat(newExpr);
                if (nv == 0.0f) writeWithNops(it, pos, origSize, new byte[]{
                    (byte) Opcode.FCONST_0
                });
                else if (nv == 1.0f) writeWithNops(it, pos, origSize, new byte[]{
                    (byte) Opcode.FCONST_1
                });
                else if (nv == 2.0f) writeWithNops(it, pos, origSize, new byte[]{
                    (byte) Opcode.FCONST_2
                });
                else {
                    int cpIdx = cp.addFloatInfo(nv);
                    writeWithNops(it, pos, origSize, new byte[]{
                        (byte) Opcode.LDC_W, (byte) (cpIdx >> 8), (byte) cpIdx 
                    });
                }
            }
            case DOUBLE -> {
                double nv = parseDouble(newExpr);
                if (nv == 0.0) writeWithNops(it, pos, origSize, new byte[]{
                    (byte) Opcode.DCONST_0
                });
                else if (nv == 1.0) writeWithNops(it, pos, origSize, new byte[]{
                    (byte) Opcode.DCONST_1
                });
                else {
                    int cpIdx = cp.addDoubleInfo(nv);
                    writeWithNops(it, pos, origSize, new byte[]{
                        (byte) Opcode.LDC2_W, (byte) (cpIdx >> 8), (byte) cpIdx
                    });
                }
            }
            case LONG -> {
                long nv = parseLong(newExpr);
                if (nv == 0L) writeWithNops(it, pos, origSize, new byte[]{
                    (byte) Opcode.LCONST_0
                });
                else if (nv == 1L) writeWithNops(it, pos, origSize, new byte[]{
                    (byte) Opcode.LCONST_1
                });
                else {
                    int cpIdx = cp.addLongInfo(nv);
                    writeWithNops(it, pos, origSize, new byte[]{
                        (byte) Opcode.LDC2_W, (byte) (cpIdx >> 8), (byte) cpIdx
                    });
                }
            }
        }
    }

    private static void replaceStringConstant(CtMethod method, String wanted, int wantOrdinal, String newLiteral, int sliceFrom, int sliceTo) throws CannotCompileException {
        MethodInfo mi = method.getMethodInfo();
        CodeAttribute ca = mi.getCodeAttribute();
        if (ca == null) return;
        CodeIterator it = ca.iterator();
        ConstPool cp = mi.getConstPool();
        int seen = 0;
        try {
            while (it.hasNext()) {
                int pos = it.next();
                int op = it.byteAt(pos);
                if (op != Opcode.LDC && op != Opcode.LDC_W) continue;
                int line = mi.getLineNumber(pos);
                if (!isInSlice(line, sliceFrom, sliceTo)) continue;
                int idx = (op == Opcode.LDC) ? it.byteAt(pos + 1) : it.u16bitAt(pos + 1);
                if (cp.getTag(idx) != ConstPool.CONST_String) continue;
                String s = cp.getStringInfo(idx);
                if (!wanted.equals(s)) continue;
                if (wantOrdinal >= 0 && seen++ != wantOrdinal) continue;
                int newIdx = cp.addStringInfo(unquote(newLiteral));
                int origSize = (op == Opcode.LDC) ? 2 : 3;
                writeWithNops(it, pos, origSize, new byte[]{
                    (byte) Opcode.LDC_W, (byte) (newIdx >> 8), (byte) newIdx
                });
            }
        } catch (BadBytecode e) {throw new CannotCompileException(e);}
    }

    private static Integer readIntConstant(CodeIterator it, ConstPool cp, int pos, int op) {
        try {
            return switch (op) {
                case Opcode.ICONST_M1 -> -1;
                case Opcode.ICONST_0 -> 0;
                case Opcode.ICONST_1 -> 1;
                case Opcode.ICONST_2 -> 2;
                case Opcode.ICONST_3 -> 3;
                case Opcode.ICONST_4 -> 4;
                case Opcode.ICONST_5 -> 5;
                case Opcode.BIPUSH -> (int) (byte) it.byteAt(pos + 1);
                case Opcode.SIPUSH -> (int) (short) it.s16bitAt(pos + 1);
                case Opcode.LDC -> intFromCp(cp, it.byteAt(pos + 1));
                case Opcode.LDC_W -> intFromCp(cp, it.u16bitAt(pos + 1));
                default -> null;
            };
        } catch (Exception e) {return null;}
    }

    private static Integer intFromCp(ConstPool cp, int idx) {
        return cp.getTag(idx) == ConstPool.CONST_Integer ? cp.getIntegerInfo(idx) : null;
    }

    private static int parseInt(String src, int fallback) {
        try {
            String s = src.trim().replaceAll("[fFdDlL]$", "");
            return Integer.parseInt(s);
        } catch (Exception e) {return fallback;}
    }

    private static float parseFloat(String src) {
        return Float.parseFloat(src.trim().replaceAll("[fF]$", ""));
    }

    private static double parseDouble(String src) {
        return Double.parseDouble(src.trim().replaceAll("[dD]$", ""));
    }

    private static long parseLong(String src) {
        return Long.parseLong(src.trim().replaceAll("[lL]$", ""));
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
            StringBuilder sb = new StringBuilder(s.length());
            for (int i=0;i<s.length();i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char next = s.charAt(++i);
                    switch (next) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '\\' -> sb.append('\\');
                        case '"' -> sb.append('"');
                        default -> {sb.append('\\'); sb.append(next);}
                    }
                } else {sb.append(c);}
            }
            return sb.toString();
        }
        return s;
    }
}