import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.tools.*;

/** javac-resolved source graph; no guessed call edges from a regular expression. */
class PersistenceTrace {
  static final String ROOT = "io.github.lovexiaopeng.jimu.";
  static final Map<String,Object> methods = new TreeMap<>();
  static Trees trees;
  static javax.lang.model.util.Elements elements;
  static javax.lang.model.util.Types types;
  static String id(Element element) {
    if(element==null)return null;
    Element owner=element.getEnclosingElement();
    return (owner instanceof TypeElement type ? elements.getBinaryName(type) : owner) + "#" + element;
  }
  static List<String> overridden(ExecutableElement method, TypeElement owner) {
    var result=new ArrayList<String>();
    for(var parent:types.directSupertypes(owner.asType())) {
      var type=(TypeElement)types.asElement(parent);
      for(var member:elements.getAllMembers(type)) if(member instanceof ExecutableElement executable && elements.overrides(method,executable,owner)) result.add(id(member));
    }
    return result.stream().distinct().toList();
  }
  static class Scan extends TreePathScanner<Void,Void> {
    final CompilationUnitTree unit;
    Map<String,Object> current;
    Scan(CompilationUnitTree unit) { this.unit=unit; }
    long line(Tree tree) { return unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit,tree)); }
    String source(Tree tree) { return tree == null ? "" : tree.toString(); }
    @Override public Void visitMethod(MethodTree tree, Void unused) {
      var previous = current;
      Element symbol = trees.getElement(getCurrentPath());
      current = new LinkedHashMap<>();
      current.put("id",id(symbol)); current.put("file",Path.of(unit.getSourceFile().toUri()).toString().replace('\\','/'));
      current.put("line",line(tree)); current.put("modifiers",tree.getModifiers().getFlags().toString());
      current.put("calls",new ArrayList<>());
      current.put("dispatch_from",symbol instanceof ExecutableElement method && symbol.getEnclosingElement() instanceof TypeElement owner ? overridden(method,owner) : List.of());
      current.put("lambda_ports",new ArrayList<>());
      methods.put(id(symbol),current);
      super.visitMethod(tree,unused); current=previous; return null;
    }
    @Override public Void visitLambdaExpression(LambdaExpressionTree tree,Void unused) {
      if(current!=null) {
        var type=types.asElement(trees.getTypeMirror(getCurrentPath()));
        if(type instanceof TypeElement owner) for(var member:elements.getAllMembers(owner))
          if(member.getKind()==ElementKind.METHOD && member.getModifiers().contains(Modifier.ABSTRACT)) ((List<Object>)current.get("lambda_ports")).add(id(member));
      }
      return super.visitLambdaExpression(tree,unused);
    }
    @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
      // Child argument expressions execute before this invocation; branch/loop labels stay explicit.
      super.visitMethodInvocation(tree,unused);
      if (current == null) return null;
      var call = new LinkedHashMap<String,Object>();
      call.put("target",id(trees.getElement(getCurrentPath()))); call.put("line",line(tree));
      call.put("select",source(tree.getMethodSelect()));
      call.put("arguments",tree.getArguments().stream().map(arg -> arg instanceof LambdaExpressionTree ? "<callback>" : source(arg).length()>2048 ? "<"+arg.getKind()+": see source>" : source(arg)).toList());
      var conditions = new ArrayList<String>();
      Tree child=tree;
      for(TreePath path=getCurrentPath().getParentPath(); path!=null && !(path.getLeaf() instanceof MethodTree); child=path.getLeaf(),path=path.getParentPath()) {
        Tree parent=path.getLeaf();
        if(parent instanceof IfTree branch) conditions.add((child==branch.getElseStatement()?"else ":"if ")+branch.getCondition());
        else if(parent instanceof ConditionalExpressionTree branch) conditions.add((child==branch.getFalseExpression()?"false ":"true ")+branch.getCondition());
        else if(parent instanceof EnhancedForLoopTree loop) conditions.add("for "+loop.getVariable()+" : "+loop.getExpression());
        else if(parent instanceof ForLoopTree loop) conditions.add("for "+loop.getCondition());
        else if(parent instanceof WhileLoopTree loop) conditions.add("while "+loop.getCondition());
        else if(parent instanceof CatchTree caught) conditions.add("catch "+caught.getParameter());
        else if(parent instanceof LambdaExpressionTree) conditions.add("callback: transaction/stream/external caller, see expression");
      }
      Collections.reverse(conditions);call.put("conditions",conditions);
      for(ExpressionTree argument:tree.getArguments()) {
        if(argument instanceof LiteralTree literal && literal.getValue() instanceof String sql && sql.matches("(?is)^(SELECT|INSERT|UPDATE|DELETE|SET|SHOW|ALTER|CREATE).*")) {
          call.put("sql",sql); break;
        }
      }
      ((List<Object>)current.get("calls")).add(call);
      return null;
    }
  }
  static String json(Object value) {
    if(value==null)return "null";
    if(value instanceof Number || value instanceof Boolean)return value.toString();
    if(value instanceof Map<?,?> map) { var out=new ArrayList<String>(); map.forEach((k,v)->out.add(json(k.toString())+":"+json(v))); return "{"+String.join(",",out)+"}"; }
    if(value instanceof Collection<?> list) return "["+String.join(",",list.stream().map(PersistenceTrace::json).toList())+"]";
    return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")+"\"";
  }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]).toAbsolutePath();
    var files=Files.walk(root.resolve("src/main/java")).filter(p->p.toString().endsWith(".java")).sorted().map(Path::toFile).toList();
    var compiler=ToolProvider.getSystemJavaCompiler();
    var diagnostics=new DiagnosticCollector<JavaFileObject>();
    try(var manager=compiler.getStandardFileManager(diagnostics,null,java.nio.charset.StandardCharsets.UTF_8)) {
      Path build=args.length>2 ? Path.of(args[2]).toAbsolutePath() : root;
      var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","--release","21","-classpath",build.resolve("target/classes")+java.io.File.pathSeparator+Files.readString(build.resolve("target/xiao60/classpath.txt")).trim()),null,manager.getJavaFileObjectsFromFiles(files));
      var units=task.parse();task.analyze();trees=Trees.instance(task);elements=task.getElements();types=task.getTypes();
      for(var diagnostic:diagnostics.getDiagnostics()) if(diagnostic.getKind()==Diagnostic.Kind.ERROR)throw new IllegalStateException(diagnostic.toString());
      for(var unit:units)new Scan(unit).scan(unit,null);
    }
    for(Object value:methods.values()) {var method=(Map<String,Object>)value; method.put("file",root.relativize(Path.of((String)method.get("file"))).toString().replace('\\','/'));}
    Files.writeString(Path.of(args[1]),json(methods),java.nio.charset.StandardCharsets.UTF_8);
    System.out.println("resolved production methods="+methods.size());
  }
}
