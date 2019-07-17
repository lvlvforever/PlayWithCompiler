package script2;

import org.antlr.v4.runtime.ParserRuleContext;

public class Variable extends Symbol {
    protected Type type = null;

    //// 作为parameter的变量的属性
    //缺省值
    protected Object defaultValue = null;
    //是否允许多次重复，这是一个创新的参数机制
    protected Integer multiplicity = 1;

    protected Variable(String name, Scope scope, ParserRuleContext ctx) {
        this.name = name;
        this.scope = scope;
        this.ctx = ctx;
    }
}