package org.enso.compiler.core;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

import org.enso.compiler.core.ir.Diagnostic;
import org.enso.compiler.core.ir.IdentifiedLocation;
import org.enso.compiler.core.ir.CallArgument;
import org.enso.compiler.core.ir.DefinitionArgument;
import org.enso.compiler.core.ir.DiagnosticStorage;
import org.enso.compiler.core.ir.Empty;
import org.enso.compiler.core.ir.Expression;
import org.enso.compiler.core.ir.Function;
import org.enso.compiler.core.ir.Literal;
import org.enso.compiler.core.ir.Name;
import org.enso.compiler.core.ir.MetadataStorage;
import org.enso.compiler.core.ir.Module;
import org.enso.compiler.core.ir.Pattern;
import org.enso.compiler.core.ir.Type;
import org.enso.compiler.core.ir.expression.Application;
import org.enso.compiler.core.ir.expression.Case;
import org.enso.compiler.core.ir.expression.Comment;
import org.enso.compiler.core.ir.expression.Foreign;
import org.enso.compiler.core.ir.expression.Operator;
import org.enso.compiler.core.ir.expression.Section;
import org.enso.compiler.core.ir.expression.errors.Syntax;
import org.enso.compiler.core.ir.module.scope.Definition;
import org.enso.compiler.core.ir.module.scope.definition.Method;
import org.enso.compiler.core.ir.module.scope.Export;
import org.enso.compiler.core.ir.module.scope.Import;
import org.enso.compiler.core.ir.module.scope.imports.Polyglot;
import org.enso.syntax.text.Location;
import org.enso.syntax2.ArgumentDefinition;
import org.enso.syntax2.Base;
import org.enso.syntax2.DocComment;
import org.enso.syntax2.Line;
import org.enso.syntax2.TextElement;
import org.enso.syntax2.Token;
import org.enso.syntax2.Tree;

import org.enso.syntax2.Tree.Invalid;

import org.enso.syntax2.Tree.Private;
import scala.Option;
import scala.collection.immutable.LinearSeq;
import scala.collection.immutable.List;
import scala.jdk.javaapi.CollectionConverters;

final class TreeToIr {
  static final TreeToIr MODULE = new TreeToIr();
  static final String SKIP_MACRO_IDENTIFIER = "SKIP";
  static final String FREEZE_MACRO_IDENTIFIER = "FREEZE";

  private TreeToIr() {
  }

  /** Translates a program represented in the parser {@link Tree} to the compiler's
    * {@link IR}.
    *
    * @param ast the tree representing the program to translate
    * @return the IR representation of `inputAST`
    */
  Module translate(Tree ast) {
    return translateModule(ast);
  }

  /**
   * Translates an inline program expression represented in the parser {@link Tree}
   * to the compiler's {@link IR} representation.
   *
   * Inline expressions must <b>only</b> be expressions, and may not contain any
   * type of definition.
   *
   * @param ast The tree representing the expression to translate.
   * @return The {@link IR} representation of the given ast if it is valid, otherwise
   *  {@link Option#empty()}.
   */
  Option<Expression> translateInline(Tree ast) {
    return switch(ast) {
      case Tree.BodyBlock b -> {
        List<Expression> expressions = nil();
        java.util.List<IdentifiedLocation> locations = new ArrayList<>();
        for (Line statement : b.getStatements()) {
          Tree exprTree = statement.getExpression();
          Expression expr = switch (exprTree) {
            case null -> null;
            case Tree.Export x -> null;
            case Tree.Import x -> null;
            case Tree.Invalid x -> null;
            case Tree.TypeSignature sig -> {
              Expression methodReference;
              try {
                methodReference = translateMethodReference(sig.getVariable(), true);
              } catch (SyntaxException ex) {
                methodReference = translateExpression(sig.getVariable());
              }
              var signature = translateType(sig.getType(), false);
              var ascription = new Type.Ascription(methodReference, signature, getIdentifiedLocation(sig), meta(), diag());
              yield ascription;
            }
            default -> translateExpression(exprTree);
          };
          if (expr != null) {
            expressions = cons(expr, expressions);
            if (expr.location().isDefined()) {
              locations.add(expr.location().get());
            }
          }
        }
        yield switch (expressions.size()) {
          case 0 -> Option.empty();
          case 1 -> Option.apply(expressions.head());
          default -> {
            Option<IdentifiedLocation> combinedLocation;
            if (locations.isEmpty()) {
              combinedLocation = Option.empty();
            } else {
              combinedLocation = Option.apply(
                      new IdentifiedLocation(
                              new Location(
                                      locations.get(1).start(),
                                      locations.get(locations.size() - 1).end()
                              ),
                              Option.empty()
                      )
              );
            }
            var returnValue = expressions.head();
            @SuppressWarnings("unchecked")
            var statements = ((List<Expression>) expressions.tail()).reverse();
            yield Option.apply(new Expression.Block(
              statements,
              returnValue,
              combinedLocation,
              false,
              meta(),
              diag()
            ));
          }
        };
      }
      default -> {
        throw new IllegalStateException();
      }
    };
  }

  /** Translate a top-level Enso module into [[IR]].
    *
    * @param module the [[AST]] representation of the module to translate
    * @return the [[IR]] representation of `module`
    */
  Module translateModule(Tree module) {
    return switch (module) {
      case Tree.BodyBlock b -> {
        boolean isPrivate = false;
        List<Definition> bindings = nil();
        List<Import> imports = nil();
        List<Export> exports = nil();
        List<Diagnostic> diag = nil();
        for (Line line : b.getStatements()) {
          var expr = line.getExpression();
          // Documentation found among imports/exports or at the top of the module (if it starts with imports) is
          // placed in `bindings` by AstToIr.
          while (expr instanceof Tree.Documented doc) {
            Definition c;
            try {
              c = translateComment(doc, doc.getDocumentation());
            } catch (SyntaxException ex) {
              c = ex.toError();
            }
            bindings = cons(c, bindings);
            expr = doc.getExpression();
          }
          if (expr instanceof Private priv) {
            if (priv.getBody() != null) {
              var error = translateSyntaxError(priv, new Syntax.UnsupportedSyntax("Private token with body"));
              diag = cons(error, diag);
            }
            if (isPrivate) {
              var error = translateSyntaxError(priv, new Syntax.UnsupportedSyntax("Private token specified more than once"));
              diag = cons(error, diag);
            }
            isPrivate = true;
            continue;
          }
          switch (expr) {
            case Tree.Import imp -> imports = cons(translateImport(imp), imports);
            case Tree.Export exp -> exports = cons(translateExport(exp), exports);
            case null -> {}
            default -> bindings = translateModuleSymbol(expr, bindings);
          }
        }
        yield new Module(imports.reverse(), exports.reverse(), bindings.reverse(), isPrivate, getIdentifiedLocation(module), meta(), DiagnosticStorage.apply(diag));
      }
      default -> new Module(
        nil(), nil(),
        cons(translateSyntaxError(module, new Syntax.UnsupportedSyntax("translateModule")), nil()),
        false,
        getIdentifiedLocation(module), meta(), diag()
      );
    };
  }

  /** Translates a module-level definition from its [[AST]] representation into
    * [[IR]].
    *
    * @param inputAst the definition to be translated
    * @param appendTo list of already collected definitions
    * @return the [[IR]] representation of `inputAST` appended
    */
  List<Definition> translateModuleSymbol(Tree inputAst, List<Definition> appendTo) {
    try {
      return translateModuleSymbolImpl(inputAst, appendTo);
    } catch (SyntaxException ex) {
      return cons(ex.toError(), appendTo);
    }
  }

  private List<Definition> translateModuleSymbolImpl(Tree inputAst, List<Definition> appendTo) throws SyntaxException {
    return switch (inputAst) {
      case null -> appendTo;

      case Tree.TypeDef def -> {
        var typeName = buildName(def.getName(), true);
        List<IR> irBody = nil();
        for (var line : def.getBody()) {
          irBody = translateTypeBodyExpression(line.getExpression(), irBody);
        }
        List<DefinitionArgument> args = translateArgumentsDefinition(def.getParams());
        var type = new Definition.SugaredType(
          typeName,
          args,
          irBody.reverse(),
          getIdentifiedLocation(inputAst),
          meta(), diag()
        );
        yield cons(type, appendTo);
      }

      case Tree.Function fn -> {
        var methodRef = translateMethodReference(fn.getName(), false);
        var args = translateArgumentsDefinition(fn.getArgs());
        var body = translateExpression(fn.getBody());

        if (body == null) {
            var error = translateSyntaxError(inputAst, new Syntax.UnsupportedSyntax("Block without body"));
            yield cons(error, appendTo);
        }
        var binding = new Method.Binding(
          methodRef,
          args,
          body,
          getIdentifiedLocation(inputAst, 0, 1, null),
          meta(), diag()
        );
        yield cons(binding, appendTo);
      }

      case Tree.ForeignFunction fn when fn.getBody() instanceof Tree.TextLiteral body -> {
        var name = fn.getName();
        var nameLoc = getIdentifiedLocation(name);
        var methodRef = new Name.MethodReference(Option.empty(), buildName(name), nameLoc, meta(), diag());
        var args = translateArgumentsDefinition(fn.getArgs());
        var languageName = fn.getLanguage().codeRepr();
        var language = languageName;
        if (language == null) {
          var message = "Language '" + languageName + "' is not a supported polyglot language.";
          var error = translateSyntaxError(inputAst, new Syntax.InvalidForeignDefinition(message));
          yield cons(error, appendTo);
        }
        var text = buildTextConstant(body, body.getElements());
        var def = new Foreign.Definition(language, text, getIdentifiedLocation(fn.getBody()), meta(), diag());
        var binding = new Method.Binding(
                methodRef, args, def, getIdentifiedLocation(inputAst), meta(), diag()
        );
        yield cons(binding, appendTo);
      }

      case Tree.AnnotatedBuiltin anno -> {
        var annotation = new Name.BuiltinAnnotation("@" + anno.getAnnotation().codeRepr(), getIdentifiedLocation(anno), meta(), diag());
        yield translateModuleSymbol(anno.getExpression(), cons(annotation, appendTo));
      }

      case Tree.Annotated anno -> {
        var annotationArgument = translateExpression(anno.getArgument());
        var annotation = new Name.GenericAnnotation(anno.getAnnotation().codeRepr(), annotationArgument, getIdentifiedLocation(anno), meta(), diag());
        yield translateModuleSymbol(anno.getExpression(), cons(annotation, appendTo));
      }

      case Tree.Documented doc -> {
        var comment = translateComment(doc, doc.getDocumentation());
        yield translateModuleSymbol(doc.getExpression(), cons(comment, appendTo));
      }

      case Tree.Assignment a -> {
        var reference = translateMethodReference(a.getPattern(), false);
        var body = translateExpression(a.getExpr());
        if (body == null) {
            throw new NullPointerException();
        }
        var aLoc = expandToContain(getIdentifiedLocation(a.getExpr()), body.location());
        var binding = new Method.Binding(
          reference,
          nil(),
          body.setLocation(aLoc),
          expandToContain(getIdentifiedLocation(a), aLoc),
          meta(), diag()
        );
        yield cons(binding, appendTo);
      }

      case Tree.TypeSignature sig -> {
        var methodReference = translateMethodReference(sig.getVariable(), true);
        var signature = translateType(sig.getType(), false);
        var ascription = new Type.Ascription(methodReference, signature, getIdentifiedLocation(sig), meta(), diag());
        yield cons(ascription, appendTo);
      }

      default -> {
        var error = translateSyntaxError(inputAst, Syntax.UnexpectedExpression$.MODULE$);
        yield cons(error, appendTo);
      }
    };
  }

  private List<DefinitionArgument> translateArgumentsDefinition(java.util.List<ArgumentDefinition> args) throws SyntaxException {
    List<DefinitionArgument> res = nil();
    for (var p : args) {
      var d = translateArgumentDefinition(p);
      res = cons(d, res);
    }
    return res.reverse();
  }

  IR translateConstructorDefinition(Tree.ConstructorDefinition cons, Tree inputAst) {
    try {
      var constructorName = buildName(inputAst, cons.getConstructor());
      List<DefinitionArgument> args = translateArgumentsDefinition(cons.getArguments());
      var cAt = getIdentifiedLocation(inputAst);
      return new Definition.Data(constructorName, args, nil(), cAt, meta(), diag());
    } catch (SyntaxException ex) {
      return ex.toError();
    }
  }

  /** Translates any expression that can be found in the body of a type
    * declaration from [[AST]] into [[IR]].
    *
    * @param exp the expression to be translated
    * @return the [[IR]] representation of `maybeParensedInput`
    */
  @SuppressWarnings("unchecked")
  private List<IR> translateTypeBodyExpression(Tree exp, List<IR> appendTo) {
    try {
      return translateTypeBodyExpressionImpl(exp, appendTo);
    } catch (SyntaxException ex) {
      return cons(ex.toError(), appendTo);
    }
  }

  private List<IR> translateTypeBodyExpressionImpl(Tree exp, List<IR> appendTo) throws SyntaxException {
    var inputAst = maybeManyParensed(exp);
    return switch (inputAst) {
      case null -> appendTo;

      case Tree.ConstructorDefinition cons -> cons(translateConstructorDefinition(cons, inputAst), appendTo);

      case Tree.TypeDef def -> {
        var ir = translateSyntaxError(def, Syntax.UnexpectedDeclarationInType$.MODULE$);
        yield cons(ir, appendTo);
      }

      case Tree.ArgumentBlockApplication app -> appendTo;

      case Tree.TypeSignature sig -> {
        var isMethod = false;
        if (sig.getVariable() instanceof Tree.Ident ident) {
          isMethod = ident.getToken().isOperatorLexically();
        }
        var typeName = translateExpression(sig.getVariable(), isMethod);
        var ir = translateTypeSignature(sig, sig.getType(), typeName);
        yield cons(ir, appendTo);
      }

      case Tree.Function fun -> {
        Name name;
        if (fun.getName() instanceof Tree.Ident ident) {
          var isMethod = ident.getToken().isOperatorLexically();
          name = buildName(getIdentifiedLocation(fun.getName()), ident.getToken(), isMethod);
        } else {
          name = buildNameOrQualifiedName(fun.getName());
        }
        var ir = translateFunction(fun, name, fun.getArgs(), fun.getBody());
        yield cons(ir, appendTo);
      }

      // In some cases this is a `Function` in IR, but an `Assignment` in Tree.
      // See: https://discord.com/channels/401396655599124480/1001476608957349917
      case Tree.Assignment assignment -> {
        var name = buildName(assignment.getPattern());
        java.util.List<ArgumentDefinition> args = java.util.Collections.emptyList();
        var ir = translateFunction(assignment, name, args, assignment.getExpr());
        yield cons(ir, appendTo);
      }

      case Tree.ForeignFunction fn when fn.getBody() instanceof Tree.TextLiteral body -> {
        var name = buildName(fn.getName());
        var args = translateArgumentsDefinition(fn.getArgs());
        var languageName = fn.getLanguage().codeRepr();
        var language = languageName;
        if (language == null) {
          var message = "Language '" + languageName + "' is not a supported polyglot language.";
          var error = translateSyntaxError(inputAst, new Syntax.InvalidForeignDefinition(message));
          yield cons(error, appendTo);
        }
        var text = buildTextConstant(body, body.getElements());
        var def = new Foreign.Definition(language, text, getIdentifiedLocation(fn.getBody()), meta(), diag());
        var binding = new Function.Binding(name, args, def, getIdentifiedLocation(fn), true, meta(), diag());
        yield cons(binding, appendTo);
      }
      case Tree.Documented doc -> {
        var irDoc = translateComment(doc, doc.getDocumentation());
        yield translateTypeBodyExpression(doc.getExpression(), cons(irDoc, appendTo));
      }

      case Tree.AnnotatedBuiltin anno -> {
        var ir = new Name.BuiltinAnnotation("@" + anno.getAnnotation().codeRepr(), getIdentifiedLocation(anno), meta(), diag());
        var annotation = translateAnnotation(ir, anno.getExpression(), nil());
        yield cons(annotation, appendTo);
      }

      case Tree.Annotated anno -> {
        var annotationArgument = translateExpression(anno.getArgument());
        var annotation = new Name.GenericAnnotation(anno.getAnnotation().codeRepr(), annotationArgument, getIdentifiedLocation(anno), meta(), diag());
        yield translateTypeBodyExpression(anno.getExpression(), cons(annotation, appendTo));
      }

      default -> {
        var ir = translateSyntaxError(inputAst, Syntax.UnexpectedDeclarationInType$.MODULE$);
        yield cons(ir, appendTo);
      }
    };
  }

  @SuppressWarnings("unchecked")
  private Application translateTypeApplication(Tree.App app) throws SyntaxException {
      List<CallArgument> args = nil();
      Tree t = app;
      Name.Literal in = null;
      while (t instanceof Tree.App tApp) {
        var typeArg = translateTypeCallArgument(tApp.getArg());
        if (typeArg.value() instanceof Name.Literal l && "in".equals(l.name())) {
            in = l.copy(
              l.copy$default$1(),
              true,
              l.copy$default$3(),
              l.copy$default$4(),
              l.copy$default$5(),
              l.copy$default$6()
            );
        } else {
            args = cons(typeArg, args);
        }
        t = tApp.getFunc();
      }
      var fullQualifiedNames = qualifiedNameSegments(t, false).reverse();
      var segments = fullQualifiedNames.length();
      var type = switch (segments) {
        case 1 -> fullQualifiedNames.head();
        default -> {
          var name = fullQualifiedNames.head();
          name = new Name.Literal(name.name(), true, name.location(), name.passData(), name.diagnostics());
          List<Name> tail = (List<Name>)fullQualifiedNames.tail();
          tail = tail.reverse();
          final Option<IdentifiedLocation> loc = getIdentifiedLocation(app);
          Name arg;
          if (segments == 2) {
            arg = tail.head();
          } else {
            arg = new Name.Qualified(tail, loc, meta(), diag());
          }
          var ca = new CallArgument.Specified(Option.empty(), arg, loc, meta(), diag());
          args = cons(ca, args);
          yield name;
        }
      };
      if (in == null) {
        return new Application.Prefix(type, args, false, getIdentifiedLocation(app), meta(), diag());
      } else {
        var fn = new CallArgument.Specified(Option.empty(), type, getIdentifiedLocation(app), meta(), diag());
        return new Operator.Binary(fn, in, args.head(), getIdentifiedLocation(app), meta(), diag());
      }
    }
    private Expression translateFunction(Tree fun, Name name, java.util.List<ArgumentDefinition> arguments, final Tree treeBody) {
      List<DefinitionArgument> args;
      try {
        args = translateArgumentsDefinition(arguments);
      } catch (SyntaxException ex) {
        return ex.toError();
      }
      var body = translateExpression(treeBody);
      if (args.isEmpty()) {
        if (body instanceof Expression.Block block) {
          // suspended block has a name and no arguments
          body = block.copy(
            block.copy$default$1(),
            block.copy$default$2(),
            block.copy$default$3(),
            true,
            block.copy$default$5(),
            block.copy$default$6(),
            block.copy$default$7()
          );
        }
        if (body == null) {
          body = translateSyntaxError(fun, Syntax.UnexpectedExpression$.MODULE$);
        }
        return new Expression.Binding(name, body,
          getIdentifiedLocation(fun), meta(), diag()
        );
      } else {
        if (body == null) {
          return translateSyntaxError(fun, Syntax.UnexpectedDeclarationInType$.MODULE$);
        }
        return new Function.Binding(name, args, body,
          getIdentifiedLocation(fun), true, meta(), diag()
        );
      }
   }

  private Type.Ascription translateTypeSignature(Tree sig, Tree type, Expression typeName) {
    var fn = translateType(type, false);
    return new Type.Ascription(typeName, fn, getIdentifiedLocation(sig), meta(), diag());
  }


  /** Translates a method reference from [[AST]] into [[IR]].
    *
    * @param sig the method reference to translate
    * @return the [[IR]] representation of `inputAst`
    */
  Name.MethodReference translateMethodReference(Tree sig, boolean alwaysLocation) throws SyntaxException {
    Name method;
    Option<Name> type;
    Option<IdentifiedLocation> loc;
    switch (sig) {
      case Tree.Ident id -> {
        type = Option.empty();
        method = buildName(id);
        loc = getIdentifiedLocation(sig);
      }
      case Tree.OprApp app when ".".equals(app.getOpr().getRight().codeRepr()) -> {
        type = Option.apply(buildQualifiedName(app.getLhs()));
        method = buildName(app.getRhs());
        if (alwaysLocation) {
          loc = getIdentifiedLocation(sig);
        } else {
          loc = Option.empty();
        }
      }
      default -> throw translateEntity(sig, "translateMethodReference");
    }
    return new Name.MethodReference(type, method,
      loc, meta(), diag()
    );
  }

  private Expression translateCall(Tree ast) {
    var args = new java.util.ArrayList<CallArgument>();
    var hasDefaultsSuspended = false;
    var tree = ast;
    for (;;) {
      switch (tree) {
        case Tree.App app when app.getArg() instanceof Tree.AutoScope -> {
          hasDefaultsSuspended = true;
          tree = app.getFunc();
        }
        case Tree.App app -> {
          var expr = translateExpression(app.getArg(), false);
          var loc = getIdentifiedLocation(app.getArg());
          args.add(new CallArgument.Specified(Option.empty(), expr, loc, meta(), diag()));
          tree = app.getFunc();
        }
        case Tree.NamedApp app -> {
          var expr = translateExpression(app.getArg(), false);
          var loc = getIdentifiedLocation(app.getArg());
          var id = buildName(app, app.getName());
          args.add(new CallArgument.Specified(Option.apply(id), expr, loc, meta(), diag()));
          tree = app.getFunc();
        }
        case Tree.DefaultApp app -> {
          var loc = getIdentifiedLocation(app.getDefault());
          var expr = buildName(app.getDefault());
          args.add(new CallArgument.Specified(Option.empty(), expr, loc, meta(), diag()));
          tree = app.getFunc();
        }
        default -> {
          Expression func;
          if (tree instanceof Tree.OprApp oprApp
                  && oprApp.getOpr().getRight() != null
                  && ".".equals(oprApp.getOpr().getRight().codeRepr())
                  && oprApp.getRhs() instanceof Tree.Ident) {
            func = translateExpression(oprApp.getRhs(), true);
            if (oprApp.getLhs() == null && args.isEmpty()) {
              return func;
            }
            if (oprApp.getLhs() != null) {
              var self = translateExpression(oprApp.getLhs(), false);
              var loc = getIdentifiedLocation(oprApp.getLhs());
              args.add(new CallArgument.Specified(Option.empty(), self, loc, meta(), diag()));
            }
          } else if (args.isEmpty()) {
            return null;
          } else {
            func = translateExpression(tree, false);
          }
          java.util.Collections.reverse(args);
          var argsList = CollectionConverters.asScala(args.iterator()).toList();
          return new Application.Prefix(
                  func, argsList,
                  hasDefaultsSuspended,
                  getIdentifiedLocation(ast),
                  meta(),
                  diag()
          );
        }
      }
    }
  }

  private Name translateOldStyleLambdaArgumentName(Tree arg, boolean[] suspended, Expression[] defaultValue) throws SyntaxException {
    return switch (arg) {
      case Tree.Group g -> translateOldStyleLambdaArgumentName(g.getBody(), suspended, defaultValue);
      case Tree.Wildcard wild -> new Name.Blank(getIdentifiedLocation(wild.getToken()), meta(), diag());
      case Tree.OprApp app when "=".equals(app.getOpr().getRight().codeRepr()) -> {
          if (defaultValue != null) {
            defaultValue[0] = translateExpression(app.getRhs(), false);
          }
          yield translateOldStyleLambdaArgumentName(app.getLhs(), suspended, null);
      }
      case Tree.Ident id -> {
        Expression identifier = translateIdent(id, false);
        yield switch (identifier) {
          case Name name_ -> name_;
          default -> throw translateEntity(id, "translateOldStyleLambdaArgumentName");
        };
      }
      case Tree.UnaryOprApp app when "~".equals(app.getOpr().codeRepr()) -> {
          if (suspended != null) {
            suspended[0] = true;
          }
          yield translateOldStyleLambdaArgumentName(app.getRhs(), null, defaultValue);
      }
      default -> throw translateEntity(arg, "translateOldStyleLambdaArgumentName");
    };
  }

  /** Translates an arbitrary program expression from {@link Tree} into {@link IR}.
   *
   * @param tree the expression to be translated
   * @return the {@link IR} representation of `tree`
   */
  Expression translateExpression(Tree tree) {
    return translateExpression(tree, false);
  }
  Expression translateExpression(Tree tree, boolean isMethod) {
    try {
      return translateExpressionImpl(tree, isMethod);
    } catch (SyntaxException ex) {
      return ex.toError();
    }
  }

  private Expression translateExpressionImpl(Tree tree, boolean isMethod) throws SyntaxException {
    if (tree == null) {
      return null;
    }
    var callExpression = translateCall(tree);
    if (callExpression != null) {
      return callExpression;
    }
    return switch (tree) {
      case Tree.OprApp app -> {
        var op = app.getOpr().getRight();
        if (op == null) {
          var at = getIdentifiedLocation(app);
          var arr = app.getOpr().getLeft().getOperators();
          if (arr.size() > 0 && arr.get(0).codeRepr().equals("=")) {
              var errLoc = arr.size() > 1 ? getIdentifiedLocation(arr.get(1)) : at;
              var err = translateSyntaxError(errLoc.get(), Syntax.UnrecognizedToken$.MODULE$);
              var name = buildName(app.getLhs());
              yield new Expression.Binding(name, err, at, meta(), diag());
          } else {
              yield translateSyntaxError(at.get(), Syntax.UnrecognizedToken$.MODULE$);
          }
        }
        yield switch (op.codeRepr()) {
          case "." -> {
            final Option<IdentifiedLocation> loc = getIdentifiedLocation(tree);
            try {
              yield buildQualifiedName(app, loc, false);
            } catch (SyntaxException ex) {
              yield ex.toError();
            }
          }

          case "->" -> {
            // Old-style lambdas; this syntax will be eliminated after the parser transition is complete.
            var arg = app.getLhs();
            if (arg == null) {
              yield translateSyntaxError(app, Syntax.UnexpectedExpression$.MODULE$);
            }
            var isSuspended = new boolean[1];
            if (arg instanceof Tree.UnaryOprApp susApp && "~".equals(susApp.getOpr().codeRepr())) {
                arg = susApp.getRhs();
                isSuspended[0] = true;
            }
            var defaultValue = new Expression[1];
            Name name = translateOldStyleLambdaArgumentName(arg, isSuspended, defaultValue);
            var arg_ = new DefinitionArgument.Specified(
                    name,
                    Option.empty(),
                    Option.apply(defaultValue[0]),
                    isSuspended[0],
                    getIdentifiedLocation(arg),
                    meta(),
                    diag()
            );
            List<DefinitionArgument> args = cons(arg_, nil());
            var body = translateExpression(app.getRhs(), false);
            if (body == null) {
              body = new Expression.Block(
                nil(), new Name.Blank(Option.empty(), meta(), diag()),
                Option.empty(), true, meta(), diag()
              );
            }
            var at = expandToContain(switch (body) {
              case Expression.Block __ -> getIdentifiedLocation(tree, 0, 1, null);
              default -> getIdentifiedLocation(tree);
            }, body.location());
            yield new Function.Lambda(args, body, at, true, meta(), diag());
          }
          default -> {
            var lhs = unnamedCallArgument(app.getLhs());
            var rhs = unnamedCallArgument(app.getRhs());
            var name = new Name.Literal(
              op.codeRepr(), true, getIdentifiedLocation(op), meta(), diag()
            );
            var loc = getIdentifiedLocation(app);
            if (lhs == null && rhs == null) {
              yield new Section.Sides(name, loc, meta(), diag());
            } else if (lhs == null) {
              yield new Section.Right(name, rhs, loc, meta(), diag());
            } else if (rhs == null) {
              yield new Section.Left(lhs, name, loc, meta(), diag());
            } else {
              yield new Operator.Binary(lhs, name, rhs, loc,meta(), diag());
            }
          }
        };
      }
      case Tree.Array arr -> {
        List<Expression> items = nil();
        if (arr.getFirst() != null) {
          var exp = translateExpression(arr.getFirst(), false);
          items = cons(exp, items);
          for (var next : arr.getRest()) {
            exp = translateExpression(next.getBody(), false);
            if (exp == null) {
              yield translateSyntaxError(arr, Syntax.UnexpectedExpression$.MODULE$);
            }
            items = cons(exp, items);
          }
        }
        yield new Application.Sequence(
          items.reverse(),
          getIdentifiedLocation(arr), meta(), diag()
        );
      }
      case Tree.Number n -> translateNumber(n);
      case Tree.Ident id -> translateIdent(id, isMethod);
      case Tree.MultiSegmentApp app -> {
        var fnName = new StringBuilder();
        var sep = "";
        List<CallArgument> args = nil();
        for (var seg : app.getSegments()) {
          var id = seg.getHeader().codeRepr();
          fnName.append(sep);
          fnName.append(id);

          var body = unnamedCallArgument(seg.getBody());
          args = cons(body, args);

          sep = "_";
        }
        var fullName = fnName.toString();
        if (fullName.equals(FREEZE_MACRO_IDENTIFIER)) {
          yield translateExpression(app.getSegments().get(0).getBody(), false);
        } else if (fullName.equals(SKIP_MACRO_IDENTIFIER)) {
          var body = app.getSegments().get(0).getBody();
          var subexpression = Objects.requireNonNullElse(applySkip(body), body);
          yield translateExpression(subexpression, false);
        }
        var fn = new Name.Literal(fullName, true, Option.empty(), meta(), diag());
        if (!checkArgs(args)) {
          yield translateSyntaxError(app, Syntax.UnexpectedExpression$.MODULE$);
        }
        yield new Application.Prefix(fn, args.reverse(), false, getIdentifiedLocation(tree), meta(), diag());
      }
      case Tree.BodyBlock body -> {
        var expressions = new java.util.ArrayList<Expression>();
        Expression last = null;
        for (var line : body.getStatements()) {
          Tree expr = line.getExpression();
          if (expr == null) {
            continue;
          }
          if (last != null) {
            expressions.add(last);
          }
          while (expr instanceof Tree.Documented doc) {
            expr = doc.getExpression();
            expressions.add(translateComment(doc, doc.getDocumentation()));
          }
          last = translateExpression(expr, false);
        }
        // If the block ended in a documentation node without an expression, last may be null;
        // the return value of the block is a doc comment.
        // (This is to match the behavior of AstToIr; after the parser transition, we should probably
        // ignore the orphaned documentation and return the last actual expression in the block.)
        if (last == null && expressions.size() > 0) {
          last = expressions.get(expressions.size() - 1);
          expressions.remove(expressions.size()-1);
        }
        var list = CollectionConverters.asScala(expressions.iterator()).toList();
        var locationWithANewLine = getIdentifiedLocation(body, 0, 0, null);
        if (last != null && last.location().isDefined() && last.location().get().end() != locationWithANewLine.get().end()) {
            var patched = new Location(last.location().get().start(), locationWithANewLine.get().end() - 1);
            var id = new IdentifiedLocation(patched, last.location().get().id());
            last = last.setLocation(Option.apply(id));
        }
        yield new Expression.Block(list, last, locationWithANewLine, false, meta(), diag());
      }
      case Tree.Assignment assign -> {
        var name = buildNameOrQualifiedName(assign.getPattern());
        var expr = translateExpression(assign.getExpr(), false);
        if (expr == null) {
          expr = translateSyntaxError(assign, Syntax.UnexpectedExpression$.MODULE$);
        }
        yield new Expression.Binding(name, expr, getIdentifiedLocation(tree), meta(), diag());
      }
      case Tree.ArgumentBlockApplication body -> {
        List<Expression> expressions = nil();
        Expression last = null;
        for (var line : body.getArguments()) {
          final Tree expr = line.getExpression();
          if (expr == null) {
            continue;
          }
          if (last != null) {
            expressions = cons(last, expressions);
          }
          last = translateExpression(expr, false);
        }
        if (last == null) {
            last = new Name.Blank(Option.empty(), meta(), diag());
        }
        var block = new Expression.Block(expressions.reverse(), last, getIdentifiedLocation(body), false, meta(), diag());
        if (body.getLhs() != null) {
          var fn = translateExpression(body.getLhs(), isMethod);
          List<CallArgument> args = nil();
          for (var line : body.getArguments()) {
            var expr = line.getExpression();
            if (expr instanceof Tree.Ident) {
                var call = translateCallArgument(expr);
                args = cons(call, args);
            }
          }
          yield switch (fn) {
            case Application.Prefix pref -> patchPrefixWithBlock(pref, block, args);
            default -> block;
          };
        } else {
          yield block;
        }
      }
      case Tree.TypeAnnotated anno -> translateTypeAnnotated(anno);
      case Tree.Group group -> {
          yield switch (translateExpression(group.getBody(), false)) {
              case null -> translateSyntaxError(group, Syntax.EmptyParentheses$.MODULE$);
              case Application.Prefix pref -> {
                  final Option<IdentifiedLocation> groupWithoutParenthesis = getIdentifiedLocation(group, 1, -1, pref.getExternalId());
                  yield pref.setLocation(groupWithoutParenthesis);
              }
              case Expression in -> in;
          };
      }
      case Tree.TextLiteral txt -> {
        try {
          yield translateLiteral(txt);
        } catch (SyntaxException ex) {
          yield ex.toError();
        }
      }
      case Tree.CaseOf cas -> {
        var expr = translateExpression(cas.getExpression(), false);
        List<Case.Branch> branches = nil();
        for (var line : cas.getCases()) {
          if (line.getCase() == null) {
            continue;
          }
          var branch = line.getCase();
          if (branch.getDocumentation() != null) {
            var comment = translateComment(cas, branch.getDocumentation());
            var loc = getIdentifiedLocation(cas);
            var doc = new Pattern.Documentation(comment.doc(), loc, meta(), diag());
            var br= new Case.Branch(
                    doc,
                    new Empty(Option.empty(), meta(), diag()),
                    loc, meta(), diag()
            );
            branches = cons(br, branches);
          }
          // A branch with no expression is used to hold any orphaned documentation at the end of the case-of
          // expression, with no case to attach it to.
          if (branch.getExpression() != null) {
            var br = new Case.Branch(
                    translatePattern(branch.getPattern()),
                    translateExpression(branch.getExpression(), false),
                    getIdentifiedLocation(branch.getExpression()), meta(), diag()
            );
            branches = cons(br, branches);
          }
        }
        yield new Case.Expr(expr, branches.reverse(), getIdentifiedLocation(tree), meta(), diag());
      }
      case Tree.Function fun -> {
        var name = buildName(fun.getName());
        yield translateFunction(fun, name, fun.getArgs(), fun.getBody());
      }
      case Tree.OprSectionBoundary bound -> translateExpression(bound.getAst(), false);
      case Tree.UnaryOprApp un when "-".equals(un.getOpr().codeRepr()) ->
        switch (translateExpression(un.getRhs(), false)) {
          case Literal.Number n -> n.copy(
            n.copy$default$1(),
            "-" + n.copy$default$2(),
            n.copy$default$3(),
            n.copy$default$4(),
            n.copy$default$5(),
            n.copy$default$6()
          );
          case Expression expr -> {
            var negate = new Name.Literal("negate", true, Option.empty(), meta(), diag());
            var arg = new CallArgument.Specified(Option.empty(), expr, expr.location(), meta(), diag());
            yield new Application.Prefix(negate, cons(arg, nil()), false, expr.location(), meta(), diag());
          }
        };
      case Tree.TypeSignature sig -> {
        var methodName = buildName(sig.getVariable());
        var methodReference = new CallArgument.Specified(
                Option.empty(),
                methodName,
                methodName.location(),
                meta(), diag()
        );
        var opName = buildName(Option.empty(), sig.getOperator(), true);
        var signature = translateTypeCallArgument(sig.getType());
        yield new Operator.Binary(methodReference, opName, signature, getIdentifiedLocation(sig), meta(), diag());
      }
      case Tree.TemplateFunction templ -> translateExpression(templ.getAst(), false);
      case Tree.Wildcard wild -> new Name.Blank(getIdentifiedLocation(wild), meta(), diag());
      case Tree.AnnotatedBuiltin anno -> {
        var ir = new Name.BuiltinAnnotation("@" + anno.getAnnotation().codeRepr(), getIdentifiedLocation(anno), meta(), diag());
        yield translateAnnotation(ir, anno.getExpression(), nil());
      }
      // Documentation can be attached to an expression in a few cases, like if someone documents a line of an
      // `ArgumentBlockApplication`. The documentation is ignored.
      case Tree.Documented docu -> translateExpression(docu.getExpression());
      case Tree.App app -> {
          var fn = translateExpression(app.getFunc(), isMethod);
          var loc = getIdentifiedLocation(app);
          if (app.getArg() instanceof Tree.AutoScope) {
              yield new Application.Prefix(fn, nil(), true, loc, meta(), diag());
          } else {
              yield fn.setLocation(loc);
          }
      }
      case Tree.Invalid __ -> translateSyntaxError(tree, Syntax.UnexpectedExpression$.MODULE$);
      default -> translateSyntaxError(tree, new Syntax.UnsupportedSyntax("translateExpression"));
    };
  }

  Tree applySkip(Tree tree) {
    // Termination:
    // Every iteration either breaks, or reduces [`tree`] to a substructure of [`tree`].
    var done = false;
    while (!done && tree != null) {
      tree = switch (tree) {
        case Tree.MultiSegmentApp app
                when FREEZE_MACRO_IDENTIFIER.equals(app.getSegments().get(0).getHeader().codeRepr()) ->
                app.getSegments().get(0).getBody();
        case Tree.Invalid ignored -> null;
        case Tree.BodyBlock ignored -> null;
        case Tree.Number ignored -> null;
        case Tree.Wildcard ignored -> null;
        case Tree.AutoScope ignored -> null;
        case Tree.ForeignFunction ignored -> null;
        case Tree.Import ignored -> null;
        case Tree.Export ignored -> null;
        case Tree.TypeDef ignored -> null;
        case Tree.TypeSignature ignored -> null;
        case Tree.ArgumentBlockApplication app -> app.getLhs();
        case Tree.OperatorBlockApplication app -> app.getLhs();
        case Tree.OprApp app -> app.getLhs();
        case Tree.Ident ident when ident.getToken().isTypeOrConstructor() -> null;
        case Tree.Ident ignored -> {
          done = true;
          yield tree;
        }
        case Tree.Group ignored -> {
          done = true;
          yield tree;
        }
        case Tree.UnaryOprApp app -> app.getRhs();
        case Tree.OprSectionBoundary section -> section.getAst();
        case Tree.TemplateFunction function -> function.getAst();
        case Tree.AnnotatedBuiltin annotated -> annotated.getExpression();
        case Tree.Documented documented -> documented.getExpression();
        case Tree.Assignment assignment -> assignment.getExpr();
        case Tree.TypeAnnotated annotated -> annotated.getExpression();
        case Tree.DefaultApp app -> app.getFunc();
        case Tree.App app when isApplication(app.getFunc()) -> app.getFunc();
        case Tree.NamedApp app when isApplication(app.getFunc()) -> app.getFunc();
        case Tree.App app -> Objects.requireNonNullElse(applySkip(app.getFunc()), app.getArg());
        case Tree.NamedApp app -> Objects.requireNonNullElse(applySkip(app.getFunc()), app.getArg());
        case Tree.MultiSegmentApp ignored -> null;
        case Tree.TextLiteral ignored -> null;
        case Tree.Function ignored -> null;
        case Tree.Lambda ignored -> null;
        case Tree.CaseOf ignored -> null;
        case Tree.Array ignored -> null;
        case Tree.Tuple ignored -> null;
        default -> null;
      };
    }
    return tree;
  }

  boolean isApplication(Tree tree) {
    return switch (tree) {
      case Tree.App ignored -> true;
      case Tree.NamedApp ignored -> true;
      case Tree.DefaultApp ignored -> true;
      default -> false;
    };
  }

  // The `insideTypeAscription` argument replicates an AstToIr quirk. Once the parser
  // transition is complete, we should eliminate it, keeping only the `false` branches.
  Expression translateType(Tree tree, boolean insideTypeAscription) {
    return switch (tree) {
      case null -> null;
      case Tree.App app -> {
        try {
          yield translateTypeApplication(app);
        } catch (SyntaxException ex) {
          yield ex.toError();
        }
      }
      case Tree.OprApp app -> {
        var op = app.getOpr().getRight();
        if (op == null) {
          yield translateSyntaxError(app, Syntax.UnexpectedExpression$.MODULE$);
        }
        yield switch (op.codeRepr()) {
          case "." -> {
            final Option<IdentifiedLocation> loc = getIdentifiedLocation(tree);
            try {
              yield buildQualifiedName(app, loc, false);
            } catch (SyntaxException ex) {
              yield ex.toError();
            }
          }
          case "->" -> {
            var literal = translateType(app.getLhs(), insideTypeAscription);
            var body = translateType(app.getRhs(), insideTypeAscription);
            if (body == null) {
              yield new Syntax(getIdentifiedLocation(app).get(), Syntax.UnexpectedExpression$.MODULE$, meta(), diag());
            }
            var args = switch (body) {
              case Type.Function fn -> {
                body = fn.result();
                yield cons(literal, fn.args());
              }
              default -> cons(literal, nil());
            };
            yield new Type.Function(args, body, Option.empty(), meta(), diag());
          }
          default -> {
            var lhs = translateTypeCallArgument(app.getLhs());
            var rhs = translateTypeCallArgument(app.getRhs());
            var name = new Name.Literal(
                    op.codeRepr(), true,
                    getIdentifiedLocation(app),
                    meta(), diag()
            );
            var loc = getIdentifiedLocation(app);
            yield new Operator.Binary(lhs, name, rhs, loc, meta(), diag());
          }
        };
      }
      case Tree.Array arr -> {
        List<Expression> items = nil();
        if (arr.getFirst() != null) {
          var exp = translateType(arr.getFirst(), false);
          items = cons(exp, items);
          for (var next : arr.getRest()) {
            exp = translateType(next.getBody(), insideTypeAscription);
            items = cons(exp, items);
          }
        }
        yield new Application.Literal.Sequence(
                items.reverse(),
                getIdentifiedLocation(arr), meta(), diag()
        );
      }
      case Tree.Ident id when insideTypeAscription -> {
        try {
          yield buildNameOrQualifiedName(id, getIdentifiedLocation(id));
        } catch (SyntaxException ex) {
          yield ex.toError();
        }
      }
      case Tree.Ident id -> buildName(getIdentifiedLocation(id), id.getToken(), false);
      case Tree.Group group -> translateType(group.getBody(), insideTypeAscription);
      case Tree.UnaryOprApp un -> translateType(un.getRhs(), insideTypeAscription);
      case Tree.Wildcard wild -> new Name.Blank(getIdentifiedLocation(wild), meta(), diag());
      case Tree.TypeAnnotated anno -> translateTypeAnnotated(anno);
      default -> translateSyntaxError(tree, new Syntax.UnsupportedSyntax("translateType"));
    };
  }
  Expression translateTypeAnnotated(Tree.TypeAnnotated anno) {
    var type = translateTypeCallArgument(anno.getType());
    var expr = translateCallArgument(anno.getExpression());
    var opName = new Name.Literal(anno.getOperator().codeRepr(), true, Option.empty(), meta(), diag());
    return new Operator.Binary(
            expr,
            opName,
            type,
            getIdentifiedLocation(anno),
            meta(), diag()
    );
  }

  @SuppressWarnings("unchecked")
  private Expression patchPrefixWithBlock(Application.Prefix pref, Expression.Block block, List<CallArgument> args) {
    if (block.expressions().isEmpty() && block.returnValue() instanceof Name.Blank) {
      return pref;
    }
    if (args.nonEmpty() && args.head() == null) {
        args = (List<CallArgument>) args.tail();
    }
    List<CallArgument> allArgs = (List<CallArgument>) pref.arguments().appendedAll(args.reverse());
    final CallArgument.Specified blockArg = new CallArgument.Specified(Option.empty(), block, block.location(), meta(), diag());
    List<CallArgument> withBlockArgs = (List<CallArgument>) allArgs.appended(blockArg);
    if (!checkArgs(withBlockArgs)) {
      return translateSyntaxError(pref.location().get(), Syntax.UnexpectedExpression$.MODULE$);
    }
    return new Application.Prefix(pref.function(), withBlockArgs, pref.hasDefaultsSuspended(), pref.location(), meta(), diag());
  }

  private Application.Prefix translateAnnotation(Name.BuiltinAnnotation ir, Tree expr, List<CallArgument> callArgs) {
    return switch (expr) {
      case Tree.App fn -> {
        var fnAsArg = translateCallArgument(fn.getArg());
        yield translateAnnotation(ir, fn.getFunc(), cons(fnAsArg, callArgs));
      }
      case Tree.NamedApp fn -> {
        var fnAsArg = translateCallArgument(fn);
        yield translateAnnotation(ir, fn.getFunc(), cons(fnAsArg, callArgs));
      }
      case Tree.ArgumentBlockApplication fn -> {
        var fnAsArg = translateCallArgument(fn.getLhs());
        var arg = translateCallArgument(expr);
        callArgs = cons(fnAsArg, cons(arg, callArgs));
        yield translateAnnotation(ir, null, callArgs);
      }
      case null -> {
        yield new Application.Prefix(ir, callArgs, false, ir.location(), meta(), diag());
      }
      default -> {
        var arg = translateCallArgument(expr);
        callArgs = cons(arg, callArgs);
        yield translateAnnotation(ir, null, callArgs);
      }
    };
  }

  Expression translateNumber(Tree.Number ast) {
    var intPart = ast.getInteger();
    final Option<String> base = switch (intPart.getBase()) {
      case Base.Binary b -> Option.apply("2");
      case Base.Hexadecimal b -> Option.apply("16");
      case Base.Octal b -> Option.apply("8");
      case null -> Option.empty();
      default -> Option.empty();
    };
    var fracPart = ast.getFractionalDigits();
    String literal = fracPart != null ? intPart.codeRepr() + "." + fracPart.getDigits().codeRepr() : intPart.codeRepr();
    return new Literal.Number(base, literal, getIdentifiedLocation(ast), meta(), diag());
  }

  Literal translateLiteral(Tree.TextLiteral txt) throws SyntaxException {
    if (txt.getClose() == null) {
      if (txt.getOpen() == null || switch (txt.getOpen().codeRepr()) {
        case "'''" -> false;
        case "\"\"\"" -> false;
        default -> true;
      }) {
        throw new SyntaxException(txt, Syntax.UnclosedTextLiteral$.MODULE$);
      }
    }
    // Splices are not yet supported in the IR.
    var value = buildTextConstant(txt, txt.getElements());
    return new Literal.Text(value, getIdentifiedLocation(txt), meta(), diag());
  }

  private String buildTextConstant(Tree at, Iterable<TextElement> elements) throws SyntaxException {
    var sb = new StringBuilder();
    TextElement error = null;
    for (var t : elements) {
      switch (t) {
        case TextElement.Section s -> sb.append(s.getText().codeRepr());
        case TextElement.Escape e -> {
          var val = e.getToken().getValue();
          if (val == -1) {
            error = t;
          } else {
            sb.appendCodePoint(val);
          }
        }
        case TextElement.Newline n -> sb.append('\n');
        default -> throw translateEntity(at, "buildTextConstant");
      }
    }
    if (error != null) {
      throw translateEntity(at, new Syntax.InvalidEscapeSequence(sb.toString()));
    }
    return sb.toString();
  }


  /** Translates an argument definition from [[AST]] into [[IR]].
   *
   * @param def the argument to translate
   * @return the [[IR]] representation of `arg`
   */
  DefinitionArgument translateArgumentDefinition(ArgumentDefinition def) throws SyntaxException {
    Tree pattern = def.getPattern();
    Name name = switch (pattern) {
      case Tree.Wildcard wild -> new Name.Blank(getIdentifiedLocation(wild.getToken()), meta(), diag());
      case Tree.Ident id -> {
        Expression identifier = translateIdent(id, false);
        yield switch (identifier) {
          case Name name_ -> name_;
          // TODO: Other types of pattern. Needs IR support.
          default -> throw translateEntity(pattern, "translateArgumentDefinition");
        };
      }
      // TODO: Other types of pattern. Needs IR support.
      default -> throw translateEntity(pattern, "translateArgumentDefinition");
    };
    boolean isSuspended = def.getSuspension() != null;
    var ascribedType = Option.apply(def.getType()).map(ascription -> translateType(ascription.getType(), true));
    var defaultValue = Option.apply(def.getDefault()).map(default_ -> translateExpression(default_.getExpression(), false));
    return new DefinitionArgument.Specified(
            name,
            ascribedType,
            defaultValue,
            isSuspended,
            getIdentifiedLocation(def),
            meta(),
            diag()
    );
  }

  /** Translates a call-site function argument from its [[AST]] representation
    * into [[IR]].
    *
    * @param arg the argument to translate
    * @return the [[IR]] representation of `arg`
    */
  CallArgument.Specified translateCallArgument(Tree arg) {
    var loc = getIdentifiedLocation(arg);
    return switch (arg) {
      case Tree.NamedApp app -> {
        var expr = translateExpression(app.getArg(), false);
        var id = sanitizeName(buildName(app, app.getName()));
        yield new CallArgument.Specified(Option.apply(id), expr, loc, meta(), diag());
      }
      case null -> null;
      default -> {
        var expr = translateExpression(arg, false);
        yield new CallArgument.Specified(Option.empty(), expr, loc, meta(), diag());
      }
    };
  }
  CallArgument.Specified translateTypeCallArgument(Tree arg) {
    var loc = getIdentifiedLocation(arg);
    var expr = translateType(arg, false);
    return new CallArgument.Specified(Option.empty(), expr, loc, meta(), diag());
  }
  CallArgument.Specified unnamedCallArgument(Tree arg) {
    if (arg == null) {
      return null;
    }
    var loc = getIdentifiedLocation(arg);
    var expr = translateExpression(arg);
    return new CallArgument.Specified(Option.empty(), expr, loc, meta(), diag());
  }

  /** Translates an arbitrary program identifier from its [[AST]] representation
    * into [[IR]].
    *
    * @param identifier the identifier to translate
    * @return the [[IR]] representation of `identifier`
    */
  Expression translateIdent(Tree identifier, boolean isMethod) {
    return switch (identifier) {
      case null -> null;
      case Tree.Ident id -> sanitizeName(buildName(getIdentifiedLocation(id), id.getToken(), isMethod));
      default -> translateSyntaxError(identifier, new Syntax.UnsupportedSyntax("translateIdent"));
    };
  }

  /** Translates a pattern in a case expression from its [[AST]] representation
    * into [[IR]].
    *
    * @param block the case pattern to translate
    * @return
    */
  Pattern translatePattern(Tree block) throws SyntaxException {
    var pattern = maybeManyParensed(block);
    var elements = unrollApp(pattern);
    var fields = translatePatternFields(elements.subList(1, elements.size()));
    return switch (elements.get(0)) {
      case Tree.Ident id when id.getToken().isTypeOrConstructor() || !fields.isEmpty() -> {
        yield new Pattern.Constructor(
                sanitizeName(buildName(id)), fields,
                getIdentifiedLocation(id), meta(), diag()
        );
      }
      case Tree.Ident id -> new Pattern.Name(buildName(id), getIdentifiedLocation(id), meta(), diag());
      case Tree.OprApp app when ".".equals(app.getOpr().getRight().codeRepr()) -> {
        var qualifiedName = buildQualifiedName(app);
        yield new Pattern.Constructor(
          qualifiedName, fields, getIdentifiedLocation(app), meta(), diag()
        );
      }
      case Tree.Wildcard wild -> translateWildcardPattern(wild);
      case Tree.TextLiteral lit ->
        new Pattern.Literal(translateLiteral(lit), getIdentifiedLocation(lit), meta(), diag());
      case Tree.Number num ->
        new Pattern.Literal((Literal) translateNumber(num), getIdentifiedLocation(num), meta(), diag());
      case Tree.UnaryOprApp num when num.getOpr().codeRepr().equals("-") -> {
        var n = (Literal.Number) translateExpression(num.getRhs());
        var t = n.copy(
          n.copy$default$1(),
          "-" + n.copy$default$2(),
          n.copy$default$3(),
          n.copy$default$4(),
          n.copy$default$5(),
          n.copy$default$6()
        );
        yield new Pattern.Literal(t, getIdentifiedLocation(num), meta(), diag());
      }
      case Tree.TypeAnnotated anno -> {
        var type = buildNameOrQualifiedName(maybeManyParensed(anno.getType()));
        var expr = buildNameOrQualifiedName(maybeManyParensed(anno.getExpression()));
        yield new Pattern.Type(expr, type instanceof Name ? (Name) type : null, Option.empty(), meta(), diag());
      }
      case Tree.Group group -> translatePattern(group.getBody());
      default -> throw translateEntity(pattern, "translatePattern");
    };
  }

  private List<Pattern> translatePatternFields(java.util.List<Tree> tail) throws SyntaxException {
    List<Pattern> args = nil();
    for (var t : tail) {
      var p = translatePattern(t);
      args = cons(p, args);
    }
    var fields = args.reverse();
    return fields;
  }

  private Pattern.Name translateWildcardPattern(Tree.Wildcard wild) {
      var at = getIdentifiedLocation(wild);
      var blank = new Name.Blank(at, meta(), diag());
      return new Pattern.Name(blank, at, meta(), diag());
  }

  private Name.Qualified buildQualifiedName(Tree t) throws SyntaxException {
    return buildQualifiedName(t, Option.empty(), false);
  }
  private Name.Qualified buildQualifiedName(Tree t, Option<IdentifiedLocation> loc, boolean generateId) throws SyntaxException {
    return new Name.Qualified(qualifiedNameSegments(t, generateId), loc, meta(), diag());
  }
  private Name buildNameOrQualifiedName(Tree t) throws SyntaxException {
    return buildNameOrQualifiedName(t, Option.empty());
  }
  private Name buildNameOrQualifiedName(Tree t, Option<IdentifiedLocation> loc) throws SyntaxException {
    var segments = qualifiedNameSegments(t, false);
    if (segments.length() == 1) {
      return segments.head();
    } else {
      return new Name.Qualified(segments, loc, meta(), diag());
    }
  }
  private java.util.List<Tree> unrollOprRhs(Tree list, String operator) throws SyntaxException {
    var segments = new java.util.ArrayList<Tree>();
    while (list instanceof Tree.OprApp) {
      var app = (Tree.OprApp)list;
      if (app.getOpr().getRight() == null || !operator.equals(app.getOpr().getRight().codeRepr())) {
        break;
      }
      if (app.getRhs() != null) {
        segments.add(app.getRhs());
      } else {
        throw translateEntity(app, Syntax.UnexpectedExpression$.MODULE$);
      }
      list = app.getLhs();
    }
    segments.add(list);
    java.util.Collections.reverse(segments);
    return segments;
  }
  private java.util.List<Tree> unrollApp(Tree list) {
    var elems = new java.util.ArrayList<Tree>();
    while (list instanceof Tree.App app) {
      elems.add(app.getArg());
      list = app.getFunc();
    }
    elems.add(list);
    java.util.Collections.reverse(elems);
    return elems;
  }
  private Name qualifiedNameSegment(Tree tree, boolean generateId) throws SyntaxException {
    return switch (tree) {
      case Tree.Ident id -> sanitizeName(buildName(id, generateId));
      case Tree.Wildcard wild -> new Name.Blank(getIdentifiedLocation(wild.getToken(), generateId), meta(), diag());
      default -> throw translateEntity(tree, "qualifiedNameSegment");
    };
  }
  private List<Name> qualifiedNameSegments(Tree t, boolean generateId) throws SyntaxException {
    List<Name> result = nil();
    var first = true;
    for (var segment : unrollOprRhs(t, ".")) {
      var qns = switch (qualifiedNameSegment(segment, generateId)) {
        case Name.Blank underscore -> {
          if (first) {
            yield underscore;
          } else {
            throw new SyntaxException(segment, Syntax.InvalidUnderscore$.MODULE$);
          }
        }
        case Name any -> any;
      };
      result = cons(qns, result);
      first = false;
    }
    return result.reverse();
  }
  private List<Name.Literal> buildNameSequence(Tree t) throws SyntaxException {
    List<Name.Literal> res = nil();
    for (var segment : unrollOprRhs(t, ",")) {
      var n = buildName(segment, true);
      res = cons(n, res);
    }
    return res.reverse();
  }

  /** Translates an import statement from its [[AST]] representation into
    * [[IR]].
    *
    * @param imp the import to translate
    * @return the [[IR]] representation of `imp`
    */
  @SuppressWarnings("unchecked")
  Import translateImport(Tree.Import imp) {
    try {
      Option<Name.Literal> rename;
      if (imp.getAs() == null) {
        rename = Option.empty();
      } else {
        rename = Option.apply(buildName(imp.getAs().getBody(), true));
      }
      if (imp.getPolyglot() != null) {
        if (!imp.getPolyglot().getBody().codeRepr().equals("java")) {
          return translateSyntaxError(imp, Syntax.UnrecognizedToken$.MODULE$);
        }
        List<Name> qualifiedName = qualifiedNameSegments(imp.getImport().getBody(), true);
        StringBuilder pkg = new StringBuilder();
        String cls = extractPackageAndName(qualifiedName, pkg);
        return new Polyglot(
                new Polyglot.Java(pkg.toString(), cls),
                rename.map(name -> name.name()),
                getIdentifiedLocation(imp),
                meta(),
                diag()
        );
      }
      var isAll = imp.getAll() != null;
      Name.Qualified qualifiedName;
      Option<List<Name.Literal>> onlyNames = Option.empty();
      if (imp.getFrom() != null) {
        qualifiedName = buildQualifiedName(imp.getFrom().getBody(), Option.empty(), true);
        if (!isAll) {
          onlyNames = Option.apply(buildNameSequence(imp.getImport().getBody()));
        }
      } else {
        qualifiedName = buildQualifiedName(imp.getImport().getBody(), Option.empty(), true);
      }
      Option<List<Name.Literal>> hidingNames;
      if (imp.getHiding() == null) {
        hidingNames = Option.empty();
      } else {
        hidingNames = Option.apply(buildNameSequence(imp.getHiding().getBody()));
      }
      return new Import.Module(
    qualifiedName, rename, isAll || onlyNames.isDefined() || hidingNames.isDefined(), onlyNames,
        hidingNames, getIdentifiedLocation(imp), false,
      meta(), diag()
      );
    } catch (SyntaxException err) {
      if (err.where instanceof Invalid invalid) {
        return err.toError(invalidImportReason(invalid.getError()));
      } else {
        return err.toError(invalidImportReason(null));
      }
    }
  }

  private Syntax.Reason invalidImportReason(String msg) {
    return new Syntax.InvalidImport(
        Objects.requireNonNullElse(msg, "Imports must have a valid module path"));
  }

  private Syntax.Reason invalidExportReason(String msg) {
    return new Syntax.InvalidExport(
        Objects.requireNonNullElse(msg, "Exports must have a valid module path"));
  }

  @SuppressWarnings("unchecked")
  private String extractPackageAndName(List<Name> qualifiedName, StringBuilder pkg) {
      String cls = null;
      for (List<Name> next = qualifiedName; !next.isEmpty();) {
        if (cls != null) {
          if (pkg.length() != 0) {
            pkg.append(".");
          }
          pkg.append(cls);
        }
        cls = next.head().name();
        next = (List<Name>) next.tail();
      }
    return cls;
  }

  /** Translates an export statement from its [[AST]] representation into
    * [[IR]].
    *
    * @param exp the export to translate
    * @return the [[IR]] representation of `imp`
    */
  @SuppressWarnings("unchecked")
  Export translateExport(Tree.Export exp) {
    try {
      Option<Name.Literal> rename;
      if (exp.getAs() == null) {
        rename = Option.empty();
      } else {
        rename = Option.apply(buildName(exp.getAs().getBody(), true));
      }
      Option<List<Name.Literal>> hidingNames;
      if (exp.getHiding() == null) {
        hidingNames = Option.empty();
      } else {
        hidingNames = Option.apply(buildNameSequence(exp.getHiding().getBody()));
      }
      Name.Qualified qualifiedName;
      Option<List<Name.Literal>> onlyNames = Option.empty();
      if (exp.getFrom() != null) {
        qualifiedName = buildQualifiedName(exp.getFrom().getBody(), Option.empty(), true);
        var onlyBodies = exp.getExport().getBody();
        if (exp.getAll() == null) {
          onlyNames = Option.apply(buildNameSequence(onlyBodies));
        }
      } else {
        qualifiedName = buildQualifiedName(exp.getExport().getBody(), Option.empty(), true);
      }
      return new Export.Module(
        qualifiedName, rename, (exp.getFrom() != null), onlyNames,
        hidingNames, getIdentifiedLocation(exp), false,
        meta(), diag()
        );
    } catch (SyntaxException err) {
      if (err.where instanceof Invalid invalid) {
        return err.toError(invalidExportReason(invalid.getError()));
      } else {
        return err.toError(invalidExportReason(null));
      }
    }
  }

  /** Translates a comment from its [[AST]] representation into its [[IR]]
    * representation.
    *
    * @param doc the comment to transform
    * @return the [[IR]] representation of `comment`
    */
  Comment.Documentation translateComment(Tree where, DocComment doc) throws SyntaxException {
    var text = buildTextConstant(where, doc.getElements());
    return new Comment.Documentation(text, getIdentifiedLocation(where), meta(), diag());
  }

  Syntax translateSyntaxError(Tree where, Syntax.Reason reason) {
    var at = getIdentifiedLocation(where).get();
    return new Syntax(at, reason, meta(), diag());
  }

  Syntax translateSyntaxError(IdentifiedLocation where, Syntax.Reason reason) {
    return new Syntax(where, reason, meta(), diag());
  }

  SyntaxException translateEntity(Tree where, String msg) throws SyntaxException {
    var reason = new Syntax.UnsupportedSyntax(msg);
    throw new SyntaxException(where, reason);
  }

  SyntaxException translateEntity(Tree where, Syntax.Reason reason) throws SyntaxException {
    throw new SyntaxException(where, reason);
  }

  private Name.Literal buildName(Token name) {
    return buildName(getIdentifiedLocation(name), name, false);
  }
  private Name.Literal buildName(Token name, boolean generateId) {
    return buildName(getIdentifiedLocation(name, generateId), name, false);
  }
  private Name.Literal buildName(Tree ident) throws SyntaxException {
    return buildName(ident, false);
  }
  private Name.Literal buildName(Tree ident, boolean generateId) throws SyntaxException {
    return switch (ident) {
      case Tree.Ident id -> buildName(getIdentifiedLocation(ident, generateId), id.getToken(), false);
      default -> throw translateEntity(ident, "buildName");
    };
  }

  private Name.Literal buildName(Tree ident, Token id) {
    return buildName(getIdentifiedLocation(ident), id, false);
  }

  private Name.Literal buildName(Option<IdentifiedLocation> loc, Token id, boolean isMethod) {
    final String name = id.codeRepr();
    return new Name.Literal(name, isMethod, loc, meta(), diag());
  }

  private Name sanitizeName(Name.Literal id) {
    return switch (id.name()) {
      case "self" -> new Name.Self(id.location(), false, id.passData(), id.diagnostics());
      case "Self" -> new Name.SelfType(id.location(), id.passData(), id.diagnostics());
      default -> id;
    };
  }

  private Option<IdentifiedLocation> expandToContain(Option<IdentifiedLocation> encapsulating, Option<IdentifiedLocation> inner) {
    if (encapsulating.isEmpty() || inner.isEmpty()) {
      return encapsulating;
    }
    var en = encapsulating.get();
    var in = inner.get();
    if (en.start() > in.start() || en.end() < in.end()) {
      var loc = new Location(
        Math.min(en.start(), in.start()),
        Math.max(en.end(), in.end())
      );
      return Option.apply(new IdentifiedLocation(loc, en.id()));
    } else {
      return encapsulating;
    }
  }

  private Option<IdentifiedLocation> getIdentifiedLocation(Tree ast) {
    var someId = Option.apply(ast.uuid());
    return getIdentifiedLocation(ast, 0, 0, someId);
  }
  private Option<IdentifiedLocation> getIdentifiedLocation(Tree ast, boolean generateId) {
    var someId = Option.apply(ast.uuid() == null && generateId ? UUID.randomUUID() : ast.uuid());
    return getIdentifiedLocation(ast, 0, 0, someId);
  }
  private Option<IdentifiedLocation> getIdentifiedLocation(Tree ast, int b, int e, Option<UUID> someId) {
    if (someId == null) {
      someId = Option.apply(ast.uuid());
    }
    return Option.apply(switch (ast) {
      case null -> null;
      default -> {
        var begin = Math.toIntExact(ast.getStartCode()) + b;
        var end = Math.toIntExact(ast.getEndCode()) + e;
        yield new IdentifiedLocation(new Location(begin, end), someId);
      }
    });
  }

  private Option<IdentifiedLocation> getIdentifiedLocation(ArgumentDefinition ast) {
    // Note: In the IR, `DefinitionArgument` is a type of AST node; in the parser, it is not an AST-level type, but a
    // type used only in specific locations. As a result, the IR expects it to have a source-code location, but the
    // parser doesn't have a span for it. Here we synthesize one. This will not be necessary if we refactor IR so that
    // `ArgumentDefinition` is not an AST node, though that change would have effects throughout the compiler and may
    // not be worthwhile if IR is expected to be replaced.
    long begin;
    if (ast.getOpen() != null) {
      begin = ast.getOpen().getStartCode();
    } else if (ast.getOpen2() != null) {
      begin = ast.getOpen2().getStartCode();
    } else if (ast.getSuspension() != null) {
      begin = ast.getSuspension().getStartCode();
    } else {
      begin = ast.getPattern().getStartCode();
    }
    int begin_ = Math.toIntExact(begin);
    long end;
    if (ast.getClose() != null) {
      end = ast.getClose().getEndCode();
    } else if (ast.getDefault() != null) {
      end = ast.getDefault().getEquals().getEndCode();
    } else if (ast.getClose2() != null) {
      end = ast.getClose2().getEndCode();
    } else if (ast.getType() != null) {
      end = ast.getType().getOperator().getEndCode();
    } else {
      end = ast.getPattern().getEndCode();
    }
    int end_ = Math.toIntExact(end);
    return Option.apply(new IdentifiedLocation(new Location(begin_, end_), Option.empty()));
  }

  private Option<IdentifiedLocation> getIdentifiedLocation(Token ast) {
    return getIdentifiedLocation(ast, false);
  }
  private Option<IdentifiedLocation> getIdentifiedLocation(Token ast, boolean generateId) {
    return Option.apply(switch (ast) {
      case null -> null;
      default -> {
        int begin = Math.toIntExact(ast.getStartCode());
        int end = Math.toIntExact(ast.getEndCode());
        var id = Option.apply(generateId ? UUID.randomUUID() : null);
        yield new IdentifiedLocation(new Location(begin, end), id);
      }
    });
  }
  private MetadataStorage meta() {
    return MetadataStorage.apply(nil());
  }
  private DiagnosticStorage diag() {
    return DiagnosticStorage.apply(nil());
  }
  @SuppressWarnings("unchecked")
  private static final <T> scala.collection.immutable.List<T> nil() {
    return (scala.collection.immutable.List<T>) scala.collection.immutable.Nil$.MODULE$;
  }
  private static final <T> scala.collection.immutable.List<T> cons(T head, scala.collection.immutable.List<T> tail) {
    return scala.collection.immutable.$colon$colon$.MODULE$.apply(head, tail);
  }

  private static Tree maybeManyParensed(Tree t) {
    for (;;) {
      switch (t) {
        case null -> {
          return null;
        }
        case Tree.Group g -> t = g.getBody();
        default -> {
          return t;
        }
      }
    }
  }

  private boolean checkArgs(List<CallArgument> args) {
    LinearSeq<CallArgument> a = args;
    while (!a.isEmpty()) {
      if (a.head() == null) {
        return false;
      }
      a = (LinearSeq<CallArgument>) a.tail();
    }
    return true;
  }

  private final class SyntaxException extends Exception {
    final Tree where;
    final Syntax.Reason reason;

    SyntaxException(Tree where, Syntax.Reason r) {
      this.where = where;
      this.reason = r;
    }

    Syntax toError() {
      return translateSyntaxError(where, reason);
    }

    Syntax toError(Syntax.Reason r) {
      return translateSyntaxError(where, r);
    }
  }
}
