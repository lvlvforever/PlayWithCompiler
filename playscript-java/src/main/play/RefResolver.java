package play;

import play.PlayScriptParser.*;

import java.util.LinkedList;
import java.util.List;

/**
 * 语义解析的第三步：引用消解和类型推断
 * 1.解析所有的本地变量引用、函数调用和类成员引用。
 * 2.类型推断：从下而上推断表达式的类型。
 * 这两件事要放在一起做，因为：
 * (1)对于变量，只有做了消解，才能推断出类型来。
 * (2)对于FunctionCall，只有把参数（表达式)的类型都推断出来，才能匹配到正确的函数（方法)。
 * (3)表达式里包含FunctionCall,所以要推导表达式的类型，必须知道是哪个Function，从而才能得到返回值。
 *
 */
public class RefResolver extends PlayScriptBaseListener {

    private AnnotatedTree cr = null;

    public RefResolver(AnnotatedTree cr) {
        this.cr = cr;
    }


    @Override
    public void exitPrimary(PrimaryContext ctx) {
        Scope scope = cr.enclosingScopeOfNode(ctx);
        Type type = null;
        if (ctx.IDENTIFIER() != null) {
            String idName = ctx.IDENTIFIER().getText();
            Variable variable = cr.lookupVariable(scope, idName);
            if (variable == null) {
                // 看看是不是函数，因为函数可以作为值来传递。这个时候，函数重名没法区分。
                Function function = cr.lookupFunction(scope, idName, null); // TODO 应该由上面传递下类型属性下来，然后精确比对
                if (function != null) {
                    cr.symbolOfNode.put(ctx, function);
                    type = function;
                } else {
                    cr.log("unknown variable or function: " + idName, ctx);
                }

            } else {
                cr.symbolOfNode.put(ctx, variable);

                type = variable.type;

                //记录所引用的外部变量，用于闭包
                if (scope instanceof Function && variable.enclosingScope != scope){
                    List<Variable> referedVariables = cr.outerReference.get(scope);
                    if (referedVariables == null){
                        referedVariables = new LinkedList<Variable>();
                        cr.outerReference.put(scope,referedVariables);
                    }
                    if(!referedVariables.contains(variable)){
                        referedVariables.add(variable);
                    }
                }
            }
        } else if (ctx.literal() != null) {
            type = cr.typeOfNode.get(ctx.literal());
        } else if (ctx.expression() != null) {
            type = cr.typeOfNode.get(ctx.expression());
        }

        //类型推断、冒泡
        cr.typeOfNode.put(ctx,type);
    }


    @Override
    public void exitFunctionCall(FunctionCallContext ctx) {
        if(ctx.IDENTIFIER() == null){  //TODO 先不处理 this和 super的情况
            return;
        }

        //TODO 临时代码，支持println
        if(ctx.IDENTIFIER().getText().equals("println")){
            return;
        }

        // 获得参数类型，这些类型已经在表达式中推断出来
        List<Type> paramTypes = new LinkedList<Type>();
        if (ctx.expressionList() != null) {
            for (ExpressionContext exp : ctx.expressionList().expression()) {
                Type type = cr.typeOfNode.get(exp);
                paramTypes.add(type);
            }
        }

        Function function = null;

        // 看看是不是点符号表达式调用的，调用的是类的方法
        if (ctx.parent instanceof ExpressionContext) {
            ExpressionContext exp = (ExpressionContext) ctx.parent;
            if (exp.bop != null && exp.bop.getType() == PlayScriptParser.DOT) {
                Symbol symbol = cr.symbolOfNode.get(exp.expression(0));
                if (symbol instanceof Variable && ((Variable) symbol).type instanceof Class) {
                    Class theClass = (Class) ((Variable) symbol).type;
                    Scope classScope = cr.node2Scope.get(theClass.ctx); // 在类的scope里去查找，不需要改变当前的scope

                    String idName = ctx.IDENTIFIER().getText();
                    //查找名称和参数类型都匹配的函数。不允许名称和参数都相同，但返回值不同的情况。
                    function = cr.lookupFunction(classScope, idName, paramTypes);
                    if (function != null) {
                        cr.symbolOfNode.put(ctx, function);
                        cr.typeOfNode.put(ctx, function.returnType);
                    } else {
                        cr.log("unable to find method " + idName + " in Class " + theClass.name, exp);
                    }

                } else {
                    cr.log("unable to resolve a class", ctx);
                }
            }
        }

        Scope scope = cr.enclosingScopeOfNode(ctx);

        //从当前Scope里查找函数(或方法)
        String idName = ctx.IDENTIFIER().getText();
        if (function == null && ctx.IDENTIFIER() != null) {
            function = cr.lookupFunction(scope, idName, paramTypes);
            if (function != null){
                cr.symbolOfNode.put(ctx, function);
                cr.typeOfNode.put(ctx, function.returnType);
            }
        }

        if (function == null) {
            // 看看是不是类的构建函数，用相同的名称查找一个class
            Class theClass = cr.lookupClass(scope, idName);
            if (theClass != null) {
                Scope classScope = cr.node2Scope.get(theClass.ctx);
                function = cr.lookupFunction(classScope, idName, paramTypes);
                if (function != null) {
                    cr.symbolOfNode.put(ctx, function);
                }
                //如果是与类名相同的方法，并且没有参数，那么就是缺省构造方法
                else if (ctx.expressionList() == null){
                    cr.symbolOfNode.put(ctx, theClass); // TODO 直接赋予class
                }
                else{
                    cr.log("unknown class constructor: " + ctx.getText(), ctx);
                }

                cr.typeOfNode.put(ctx, theClass); // 这次函数调用是返回一个对象
            }

            //看看是不是一个函数型的变量
            else{
                Variable variable = cr.lookupVariable(scope, idName);
                if (variable != null && variable.type instanceof FunctionType){
                    cr.symbolOfNode.put(ctx, variable);
                    cr.typeOfNode.put(ctx, variable.type);
                }
                else {
                    cr.log("unknown function or function variable: " + ctx.getText(), ctx);
                }
            }

        }

    }

    //消解处理点符号表达式的层层引用
    @Override
    public void exitExpression(ExpressionContext ctx) {
        Type type = null;

        if (ctx.bop != null && ctx.bop.getType() == PlayScriptParser.DOT) {
            // 这是个左递归，要不断的把左边的节点的计算结果存到node2Symbol，所以要在exitExpression里操作
            Symbol symbol = cr.symbolOfNode.get(ctx.expression(0));
            if (symbol instanceof Variable && ((Variable) symbol).type instanceof Class) {
                Class theClass = (Class) ((Variable) symbol).type;
                Scope classScope = cr.node2Scope.get(theClass.ctx); // 在类的scope里去查找，不需要改变当前的scope

                //引用类的属性
                if (ctx.IDENTIFIER() != null) {
                    String idName = ctx.IDENTIFIER().getText();
                    Variable variable = cr.lookupVariable(classScope, idName);
                    if (variable != null) {
                        cr.symbolOfNode.put(ctx, variable);
                        type = variable.type;  //类型综合（冒泡)
                    } else {
                        cr.log("unable to find field " + idName + " in Class " + theClass.name, ctx);
                    }
                }

                //引用类的方法
                else if (ctx.functionCall() != null){
                    type = cr.typeOfNode.get(ctx.functionCall());
                }

            } else {
                cr.log("symbol is not a qualified object：" + symbol, ctx);
            }
        }

        //变量引用冒泡： 如果下级是一个变量，往上冒泡传递，以便在点符号表达式中使用
        else if (ctx.primary() != null && ctx.primary().IDENTIFIER() != null) {
            Symbol symbol = cr.symbolOfNode.get(ctx.primary());
            cr.symbolOfNode.put(ctx, symbol);
        }


        //类型推断和综合
        if (ctx.primary() != null) {
            type = cr.typeOfNode.get(ctx.primary());
        } else if (ctx.functionCall() != null) {
            type = cr.typeOfNode.get(ctx.functionCall());
        } else if (ctx.bop != null && ctx.expression().size() >= 2) {
            Type type1 = cr.typeOfNode.get(ctx.expression(0));
            Type type2 = cr.typeOfNode.get(ctx.expression(1));

            switch (ctx.bop.getType()) {
                case PlayScriptParser.ADD:
                case PlayScriptParser.SUB:
                case PlayScriptParser.MUL:
                case PlayScriptParser.DIV:
                    if (type1 instanceof PrimitiveType && type2 instanceof PrimitiveType){
                        //类型“向上”对齐，比如一个int和一个float，取float
                        type = PrimitiveType.getUpperType(type1,type2);
                    }else{
                        cr.log("operand should be PrimitiveType for additive and multiplicative operation", ctx);
                    }

                    break;
                case PlayScriptParser.EQUAL:
                case PlayScriptParser.NOTEQUAL:
                case PlayScriptParser.LE:
                case PlayScriptParser.LT:
                case PlayScriptParser.GE:
                case PlayScriptParser.GT:
                    type = PrimitiveType.Boolean;
                    break;
                case PlayScriptParser.ASSIGN:
                case PlayScriptParser.ADD_ASSIGN:
                case PlayScriptParser.SUB_ASSIGN:
                case PlayScriptParser.MUL_ASSIGN:
                case PlayScriptParser.DIV_ASSIGN:
                case PlayScriptParser.AND_ASSIGN:
                case PlayScriptParser.OR_ASSIGN:
                case PlayScriptParser.XOR_ASSIGN:
                case PlayScriptParser.MOD_ASSIGN:
                case PlayScriptParser.LSHIFT_ASSIGN:
                case PlayScriptParser.RSHIFT_ASSIGN:
                case PlayScriptParser.URSHIFT_ASSIGN:
                    type = type1;
                    break;
            }
        }

        //类型冒泡
        cr.typeOfNode.put(ctx, type);

    }


    //对变量初始化部分也做一下类型推断
    @Override
    public void exitVariableInitializer(VariableInitializerContext ctx) {
        if (ctx.expression() != null){
            cr.typeOfNode.put(ctx, cr.typeOfNode.get(ctx.expression()));
        }
    }

    //根据字面量来推断类型
    @Override
    public void exitLiteral(LiteralContext ctx) {
        if (ctx.BOOL_LITERAL() != null) {
            cr.typeOfNode.put(ctx, PrimitiveType.Boolean);
        } else if (ctx.CHAR_LITERAL() != null) {
            cr.typeOfNode.put(ctx, PrimitiveType.Char);
        } else if (ctx.NULL_LITERAL() != null) {
            cr.typeOfNode.put(ctx, PrimitiveType.Null);
        } else if (ctx.STRING_LITERAL() != null) {
            cr.typeOfNode.put(ctx, PrimitiveType.String);
        } else if (ctx.integerLiteral() != null) {
            cr.typeOfNode.put(ctx, PrimitiveType.Integer);
        } else if (ctx.floatLiteral() != null) {
            cr.typeOfNode.put(ctx, PrimitiveType.Float);
        }

    }


}