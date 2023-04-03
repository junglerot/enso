//! Utilities for dealing with operators and Ast nodes related to them, like `Infix`, `Section*`.

use crate::prelude::*;

use crate::assoc::Assoc;
use crate::crumbs::Crumb;
use crate::crumbs::InfixCrumb;
use crate::crumbs::Located;
use crate::crumbs::SectionLeftCrumb;
use crate::crumbs::SectionRightCrumb;
use crate::crumbs::SectionSidesCrumb;
use crate::known;
use crate::Ast;
use crate::Id;
use crate::Infix;
use crate::Opr;
use crate::SectionLeft;
use crate::SectionRight;
use crate::SectionSides;
use crate::Shape;
use crate::Var;



// =================
// === Constants ===
// =================

/// Symbols that can appear in operator name, as per
/// https://enso.org/docs/developer/enso/syntax/naming.html#operator-naming
pub const SYMBOLS: [char; 25] = [
    '!', '$', '%', '&', '*', '+', '-', '/', '<', '>', '?', '^', '~', '|', ':', '\\', ',', '.', '(',
    ')', '[', ']', '{', '}', '=',
];

/// Identifiers of operators with special meaning for IDE.
pub mod predefined {
    /// Used to create type paths (like `Int.+` or `IO.println`).
    pub const ACCESS: &str = ".";
    /// Used to create bindings, e.g. `add a b = a + b` or `foo = 5`.
    pub const ASSIGNMENT: &str = "=";
    /// Used to create lambda expressions, e.g. `a -> b -> a + b`.
    pub const ARROW: &str = "->";
    /// Used to create right-associative operators, e.g. `a <| b <| c`.
    pub const RIGHT_ASSOC: &str = "<|";
}



// ====================
// === AST handling ===
// ====================

/// Checks if the given AST has Opr shape with the name matching given string.
pub fn is_opr_named(ast: &Ast, name: impl Str) -> bool {
    let name_ref = name.as_ref();
    matches!(ast.shape(), Shape::Opr(Opr { name, .. }) if name == name_ref)
}

/// Checks if given Ast is an assignment operator identifier.
pub fn is_assignment_opr(ast: &Ast) -> bool {
    is_opr_named(ast, predefined::ASSIGNMENT)
}

/// Checks if given Ast is an arrow operator identifier.
pub fn is_arrow_opr(ast: &Ast) -> bool {
    is_opr_named(ast, predefined::ARROW)
}

/// Checks if given Ast is an access operator identifier.
pub fn is_access_opr(ast: &Ast) -> bool {
    is_opr_named(ast, predefined::ACCESS)
}

/// Checks if given Ast is a right-associative operator identifier.
pub fn is_right_assoc_opr(ast: &Ast) -> bool {
    is_opr_named(ast, predefined::RIGHT_ASSOC)
}

/// Interpret Ast as accessor chain, like `Int.method`.
///
/// Returns `None` if the parameter is not an access.
pub fn as_access_chain(ast: &Ast) -> Option<Chain> {
    Chain::try_new_of(ast, predefined::ACCESS)
}

/// If given Ast is a specific infix operator application, returns it.
pub fn to_specific_infix(ast: &Ast, name: &str) -> Option<known::Infix> {
    let infix = known::Infix::try_from(ast).ok()?;
    is_opr_named(&infix.opr, name).then_some(infix)
}

/// If given Ast is an assignment infix expression, returns it as Some known::Infix.
pub fn to_assignment(ast: &Ast) -> Option<known::Infix> {
    to_specific_infix(ast, predefined::ASSIGNMENT)
}

/// If given Ast is an arrow infix expression, returns it as Some known::Infix.
pub fn to_arrow(ast: &Ast) -> Option<known::Infix> {
    to_specific_infix(ast, predefined::ARROW)
}

/// If given Ast is an access infix expression, returns it as Some known::Infix.
pub fn to_access(ast: &Ast) -> Option<known::Infix> {
    to_specific_infix(ast, predefined::ACCESS)
}

/// Checks if a given node is an access infix expression.
pub fn is_access(ast: &Ast) -> bool {
    matches!(ast.shape(), Shape::Infix(Infix { opr, .. }) if is_access_opr(opr))
}

/// Checks if a given node is an assignment infix expression.
pub fn is_assignment(ast: &Ast) -> bool {
    matches!(ast.shape(), Shape::Infix(Infix { opr, .. }) if is_assignment_opr(opr))
}

/// Obtains a new `Opr` with an assignment.
pub fn assignment() -> known::Opr {
    // TODO? We could cache and reuse, if we care.
    let name = predefined::ASSIGNMENT.into();
    let opr = Opr { name, right_assoc: false };
    known::Opr::new(opr, None)
}

/// Create a new [`ACCESS`] operator.
pub fn access() -> known::Opr {
    let name = predefined::ACCESS.into();
    let opr = Opr { name, right_assoc: false };
    known::Opr::new(opr, None)
}

/// Create a new [`RIGHT_ASSOC`] operator.
pub fn right_assoc() -> known::Opr {
    let name = predefined::RIGHT_ASSOC.into();
    let opr = Opr { name, right_assoc: true };
    known::Opr::new(opr, None)
}

/// Split qualified name into segments, like `"Int.add"` into `["Int","add"]`.
pub fn name_segments(name: &str) -> impl Iterator<Item = &str> {
    name.split(predefined::ACCESS)
}

/// Create a chain of access operators representing a fully qualified name, like `"Int.add"`.
pub fn qualified_name_chain(
    mut segments: impl Iterator<Item = impl Into<String>>,
) -> Option<Chain> {
    let ast_from_identifier = |ident: &str| -> Ast {
        let starts_with_uppercase = |s: &str| s.chars().next().map_or(false, |c| c.is_uppercase());
        if starts_with_uppercase(ident) {
            known::Cons::new(crate::Cons { name: ident.into() }, None).into()
        } else {
            known::Var::new(crate::Var { name: ident.into() }, None).into()
        }
    };
    let arg_with_offset = |s: &str| ArgWithOffset { arg: ast_from_identifier(s), offset: 0 };
    let target = segments.next()?;
    let target = Some(arg_with_offset(target.into().as_str()));
    let args = segments
        .map(|segment| ChainElement {
            operator: access(),
            operand:  Some(arg_with_offset(segment.into().as_str())),
            offset:   0,
            infix_id: None,
        })
        .collect_vec();
    let operator = access();
    Some(Chain { target, args, operator })
}



// =======================
// === Named arguments ===
// =======================

/// Matched AST fragments for named argument, flattened into easy to access structure.
#[allow(missing_docs)]
#[derive(Debug)]
pub struct NamedArgumentDef<'a> {
    pub id:   Option<Id>,
    pub name: &'a str,
    pub larg: &'a Ast,
    pub loff: usize,
    pub opr:  &'a Ast,
    pub roff: usize,
    pub rarg: &'a Ast,
}

/// Match AST against named argument pattern. Pack AST fragments into flat `NamedArgumentDef`
/// structure. Does not clone or allocate.
///
/// ```text
/// name=expression - Infix
/// name              |- Var
///     =             |- Opr ASSIGN
///      expression   `- any Ast
/// ```
pub fn match_named_argument(ast: &Ast) -> Option<NamedArgumentDef<'_>> {
    let id = ast.id;
    match ast.shape() {
        Shape::Infix(Infix { larg, loff, opr, roff, rarg }) if is_assignment_opr(opr) =>
            match larg.shape() {
                Shape::Var(Var { name }) =>
                    Some(NamedArgumentDef { id, name, larg, loff: *loff, opr, roff: *roff, rarg }),
                _ => None,
            },
        _ => None,
    }
}



// ===========================
// === Chain-related types ===
// ===========================

/// A structure which keeps argument's AST with information about offset between it and an operator.
/// We cannot use `Shifted` because `Shifted` assumes that offset is always before ast it contains,
/// what is not a case here.
#[allow(missing_docs)]
#[derive(Clone, Debug)]
pub struct ArgWithOffset<T> {
    pub arg:    T,
    pub offset: usize,
}

/// Infix operator operand. Optional, as we deal with Section* nodes as well.
pub type Operand = Option<ArgWithOffset<Ast>>;

/// Infix operator standing between (optional) operands.
pub type Operator = known::Opr;

/// Creates `Operand` from `ast` with offset between it and operator.
pub fn make_operand(arg: Ast, offset: usize) -> Operand {
    Some(ArgWithOffset { arg, offset })
}

/// Creates `Operator` from `ast`.
pub fn make_operator(opr: &Ast) -> Option<Operator> {
    known::Opr::try_from(opr).ok()
}

/// Describes associativity of the given operator AST.
pub fn assoc(ast: &known::Opr) -> Assoc {
    match ast.right_assoc {
        true => Assoc::Right,
        false => Assoc::Left,
    }
}



// ========================
// === GeneralizedInfix ===
// ========================

/// An abstraction over `Infix` and all `SectionSth` nodes. Stores crumb locations for all its ASTs.
#[derive(Clone, Debug)]
pub struct GeneralizedInfix {
    /// Left operand, if present.
    pub left:  Operand,
    /// The operator, always present.
    pub opr:   Operator,
    /// Right operand, if present.
    pub right: Operand,
    /// Infix id.
    pub id:    Option<Id>,
}

/// A structure used for GeneralizedInfix construction which marks operands as _target_ and
/// _argument_. See `target_operand` and `argument_operand` methods.
pub struct MarkedOperands {
    /// The self operand, target of the application.
    pub target:   Operand,
    /// Operand other than self.
    pub argument: Operand,
}

impl GeneralizedInfix {
    /// Tries interpret given AST node as GeneralizedInfix. Returns None, if Ast is not any kind of
    /// application on infix operator.
    pub fn try_new(ast: &Ast) -> Option<GeneralizedInfix> {
        let id = ast.id;
        match ast.shape().clone() {
            Shape::Infix(infix) => Some(GeneralizedInfix {
                id,
                left: make_operand(infix.larg, infix.loff),
                opr: make_operator(&infix.opr)?,
                right: make_operand(infix.rarg, infix.roff),
            }),
            Shape::SectionLeft(left) => Some(GeneralizedInfix {
                id,
                left: make_operand(left.arg, left.off),
                opr: make_operator(&left.opr)?,
                right: None,
            }),
            Shape::SectionRight(right) => Some(GeneralizedInfix {
                id,
                left: None,
                opr: make_operator(&right.opr)?,
                right: make_operand(right.arg, right.off),
            }),
            Shape::SectionSides(sides) => Some(GeneralizedInfix {
                id,
                left: None,
                opr: make_operator(&sides.opr)?,
                right: None,
            }),
            _ => None,
        }
    }

    /// Constructor with operands marked as target and argument.
    pub fn new_from_operands(operands: MarkedOperands, opr: Operator, id: Option<Id>) -> Self {
        match assoc(&opr) {
            Assoc::Left =>
                GeneralizedInfix { opr, id, left: operands.target, right: operands.argument },
            Assoc::Right =>
                GeneralizedInfix { opr, id, left: operands.argument, right: operands.target },
        }
    }

    /// Convert to AST node.
    pub fn into_ast(self) -> Ast {
        let ast: Ast = match (self.left, self.right) {
            (Some(left), Some(right)) => Infix {
                larg: left.arg,
                loff: left.offset,
                opr:  self.opr.into(),
                roff: right.offset,
                rarg: right.arg,
            }
            .into(),
            (Some(left), None) =>
                SectionLeft { arg: left.arg, off: left.offset, opr: self.opr.into() }.into(),
            (None, Some(right)) =>
                SectionRight { opr: self.opr.into(), off: right.offset, arg: right.arg }.into(),
            (None, None) => SectionSides { opr: self.opr.into() }.into(),
        };
        if let Some(id) = self.id {
            ast.with_id(id)
        } else {
            ast
        }
    }

    /// Associativity of the operator used in this infix expression.
    pub fn assoc(&self) -> Assoc {
        assoc(&self.opr)
    }

    /// Identifier name  of the operator used in this infix expression.
    pub fn name(&self) -> &str {
        &self.opr.name
    }

    /// The self operand, target of the application.
    pub fn target_operand(&self) -> &Operand {
        match self.assoc() {
            Assoc::Left => &self.left,
            Assoc::Right => &self.right,
        }
    }

    /// Operand other than self.
    pub fn argument_operand(&self) -> &Operand {
        match self.assoc() {
            Assoc::Left => &self.right,
            Assoc::Right => &self.left,
        }
    }

    /// Converts chain of infix applications using the same operator into `Chain`.
    /// Sample inputs are `x,y,x` or `a+b+` or `+5+5+5`. Note that `Sides*` nodes
    /// are also supported, along the `Infix` nodes.
    pub fn flatten(&self) -> Chain {
        self.flatten_with_offset(0)
    }

    fn flatten_with_offset(&self, offset: usize) -> Chain {
        let target = self.target_operand().clone();
        let rest = ChainElement {
            offset,
            operator: self.opr.clone(),
            operand: self.argument_operand().clone(),
            infix_id: self.id,
        };

        let rest_offset = rest.operand.as_ref().map_or_default(|op| op.offset);

        let target_subtree_infix = target.clone().and_then(|arg| {
            let offset = arg.offset;
            GeneralizedInfix::try_new(&arg.arg).map(|arg| ArgWithOffset { arg, offset }).filter(
                |target_infix| {
                    // For access operators, do not flatten them if there is a space before the dot.
                    // For example, `Foo . Bar . Baz` should not be flattened to `Foo.Bar.Baz`, as
                    // those should be treated as potential separate prefix expressions, allowing
                    // operator placeholders to be inserted.
                    rest_offset == 0 || target_infix.arg.name() != predefined::ACCESS
                },
            )
        });
        let mut target_subtree_flat = match target_subtree_infix {
            Some(target_infix) if target_infix.arg.name() == self.name() =>
                target_infix.arg.flatten_with_offset(target_infix.offset),
            _ => Chain { target, args: Vec::new(), operator: self.opr.clone() },
        };

        target_subtree_flat.args.push(rest);
        target_subtree_flat
    }
}

impl From<GeneralizedInfix> for Ast {
    fn from(infix: GeneralizedInfix) -> Self {
        infix.into_ast()
    }
}



// =============
// === Chain ===
// =============

/// Result of flattening infix operator chain, like `a+b+c` or `Foo.Bar.Baz`.
#[derive(Clone, Debug)]
pub struct Chain {
    /// The primary application target (left- or right-most operand, depending on
    /// operators associativity).
    pub target:   Operand,
    /// Subsequent operands applied to the `target`.
    pub args:     Vec<ChainElement>,
    /// Operator AST. Generally all operators in the chain should be the same (except for id).
    /// It is not specified exactly which operators in the chain this AST belongs to.
    pub operator: known::Opr,
}

impl Chain {
    /// If this is infix, it flattens whole chain and returns result.
    /// Otherwise, returns None.
    pub fn try_new(ast: &Ast) -> Option<Chain> {
        GeneralizedInfix::try_new(ast).map(|infix| infix.flatten())
    }

    /// Flattens infix chain if this is infix application of given operator.
    pub fn try_new_of(ast: &Ast, operator: &str) -> Option<Chain> {
        let infix = GeneralizedInfix::try_new(ast)?;
        (infix.name() == operator).as_some_from(|| infix.flatten())
    }

    /// Iterates over operands beginning with target (this argument) and then subsequent
    /// arguments.
    pub fn enumerate_operands(
        &self,
    ) -> impl Iterator<Item = Option<Located<&ArgWithOffset<Ast>>>> + '_ {
        let rev_args = self.args.iter().rev();
        let target_crumbs = rev_args.map(ChainElement::crumb_to_previous).collect_vec();
        let target = self.target.as_ref();
        let loc_target = std::iter::once(target.map(|opr| Located::new(target_crumbs, opr)));
        let args = self.args.iter().enumerate();
        let loc_args = args.map(move |(i, elem)| {
            elem.operand.as_ref().map(|operand| {
                let latter_args = self.args.iter().skip(i + 1);
                let to_infix = latter_args.rev().map(ChainElement::crumb_to_previous);
                let has_target = self.target.is_some() || i > 0;
                let crumbs = to_infix.chain(elem.crumb_to_operand(has_target)).collect_vec();
                Located::new(crumbs, operand)
            })
        });
        loc_target.chain(loc_args)
    }


    /// Iterates over non-empty operands beginning with target (this argument) and then subsequent
    /// arguments.
    pub fn enumerate_non_empty_operands(
        &self,
    ) -> impl Iterator<Item = Located<&ArgWithOffset<Ast>>> + '_ {
        self.enumerate_operands().flatten()
    }

    /// Iterates over all operator's AST in this chain, starting from target side.
    pub fn enumerate_operators(&self) -> impl Iterator<Item = Located<&known::Opr>> + '_ {
        self.args.iter().enumerate().map(move |(i, elem)| {
            let to_infix = self.args.iter().skip(i + 1).rev().map(ChainElement::crumb_to_previous);
            let has_target = self.target.is_some() || i > 0;
            let crumbs = to_infix.chain(elem.crumb_to_operator(has_target)).collect_vec();
            Located::new(crumbs, &elem.operator)
        })
    }

    /// Insert new operand at index. The target's index is 0, the first argument index is 1, and so
    /// on. So inserting at index 0 will actually set the new operand as a new target, and the old
    /// target will became the first argument.
    ///
    /// Indexing does not skip `None` operands. Function panics, if get index greater than operands
    /// count.
    pub fn insert_operand(&mut self, at_index: usize, operand: ArgWithOffset<Ast>) {
        let offset = operand.offset;
        let mut operand = Some(operand);
        let operator = self.operator.clone_ref();
        let before_target = at_index == 0;
        let infix_id: Option<Id> = None;
        if before_target {
            std::mem::swap(&mut operand, &mut self.target);
            self.args.insert(0, ChainElement { operator, operand, offset, infix_id })
        } else {
            self.args.insert(at_index - 1, ChainElement { operator, operand, offset, infix_id })
        }
    }

    /// Add operand as a new last argument.
    pub fn push_operand(&mut self, operand: ArgWithOffset<Ast>) {
        let last_index = self.args.len() + 1;
        self.insert_operand(last_index, operand)
    }

    /// Erase the current target from chain, and make the current first operand a new target.
    /// Panics if there is no operand besides target.
    pub fn erase_target(&mut self) {
        let new_target = self.args.remove(0).operand;
        self.target = new_target
    }

    /// Erase `n` leading arguments from chain (including target), and make the next remaining
    /// argument a new target. Panics if there are not enough arguments to remove.
    pub fn erase_leading_operands(&mut self, n: usize) {
        if n == 0 {
            return;
        }
        let last_removed_arg = self.args.drain(0..n).next_back();
        self.target = last_removed_arg.expect("Not enough operands to erase").operand;
    }

    /// Replace the target and first argument with a new target being an proper Infix or Section
    /// ast node. Does nothing if there are no more operands than target.
    pub fn fold_arg(&mut self) {
        if let Some(element) = self.args.pop_front() {
            let target = std::mem::take(&mut self.target);
            let operator = element.operator;
            let argument = element.operand;
            let operands = MarkedOperands { target, argument };
            let id = element.infix_id;
            let new_infix = GeneralizedInfix::new_from_operands(operands, operator, id);
            let new_with_offset =
                ArgWithOffset { arg: new_infix.into_ast(), offset: element.offset };
            self.target = Some(new_with_offset)
        }
    }

    /// Consumes the chain and returns AST node generated from it. The ids of all Infixes and
    /// Section don't preserve from any AST which was used to generate this chain.
    ///
    /// Panics if called on chain with `None` target and empty arguments list.
    pub fn into_ast(mut self) -> Ast {
        while !self.args.is_empty() {
            self.fold_arg()
        }
        if let Some(target) = self.target {
            target.arg
        } else {
            SectionSides { opr: self.operator.into() }.into()
        }
    }

    /// True if all operands are set, i.e. there are no section shapes in this chain.
    pub fn all_operands_set(&self) -> bool {
        self.target.is_some() && self.args.iter().all(|arg| arg.operand.is_some())
    }

    /// Try to convert the chain into a list of qualified name segments. Qualified name consists of
    /// identifiers chained by [`ACCESS`] operator.
    pub fn as_qualified_name_segments(&self) -> Option<Vec<ImString>> {
        let every_operator_is_access = self
            .enumerate_operators()
            .all(|opr| opr.item.ast().repr() == crate::opr::predefined::ACCESS);
        let name_segments: Option<Vec<_>> = self
            .enumerate_operands()
            .flatten()
            .map(|opr| crate::identifier::name(&opr.item.arg).map(ImString::new))
            .collect();
        let name_segments = name_segments?;
        if every_operator_is_access && !name_segments.is_empty() {
            Some(name_segments)
        } else {
            None
        }
    }
}

impl From<Chain> for Ast {
    fn from(chain: Chain) -> Self {
        chain.into_ast()
    }
}


// === Chain Element ===

/// Element of the infix application chain, i.e. operator and its operand.
#[derive(Clone, Debug)]
pub struct ChainElement {
    #[allow(missing_docs)]
    pub operator: Operator,
    /// Operand on the opposite side to `this` argument.
    /// Depending on operator's associativity it is either right (for left-associative operators)
    /// or on the left side of operator.
    pub operand:  Operand,
    /// Offset between this operand and the next operator.
    pub offset:   usize,
    /// Id of infix AST which applies this operand.
    pub infix_id: Option<Id>,
}

impl ChainElement {
    /// Return AST crumb to the node being a chain of previous operands. It assumes that such
    /// node exists.
    pub fn crumb_to_previous(&self) -> Crumb {
        let has_operand = self.operand.is_some();
        match assoc(&self.operator) {
            Assoc::Left if has_operand => InfixCrumb::LeftOperand.into(),
            Assoc::Left => SectionLeftCrumb::Arg.into(),
            Assoc::Right if has_operand => InfixCrumb::RightOperand.into(),
            Assoc::Right => SectionRightCrumb::Arg.into(),
        }
    }

    /// Return AST crumb to the operand, assuming that this operand exists.
    pub fn crumb_to_operand(&self, has_target: bool) -> Crumb {
        match assoc(&self.operator) {
            Assoc::Left if has_target => InfixCrumb::RightOperand.into(),
            Assoc::Left => SectionRightCrumb::Arg.into(),
            Assoc::Right if has_target => InfixCrumb::LeftOperand.into(),
            Assoc::Right => SectionLeftCrumb::Arg.into(),
        }
    }

    /// Return AST crumb to the operator.
    pub fn crumb_to_operator(&self, has_target: bool) -> Crumb {
        let has_operand = self.operand.is_some();
        match assoc(&self.operator) {
            _ if has_target && has_operand => InfixCrumb::Operator.into(),
            Assoc::Left if has_target => SectionLeftCrumb::Opr.into(),
            Assoc::Left if has_operand => SectionRightCrumb::Opr.into(),
            Assoc::Right if has_target => SectionRightCrumb::Opr.into(),
            Assoc::Right if has_operand => SectionLeftCrumb::Opr.into(),
            _ => SectionSidesCrumb.into(),
        }
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    fn expect_at(operand: &Operand, expected_ast: &Ast) {
        assert_eq!(&operand.as_ref().unwrap().arg, expected_ast);
    }

    fn test_enumerating(chain: &Chain, root_ast: &Ast, expected_asts: &[&Ast]) {
        assert_eq!(chain.enumerate_non_empty_operands().count(), expected_asts.len());
        for (elem, expected) in chain.enumerate_non_empty_operands().zip(expected_asts) {
            assert_eq!(elem.item.arg, **expected);
            let ast = root_ast.get_traversing(&elem.crumbs).unwrap();
            assert_eq!(ast, *expected);
        }
    }

    #[test]
    fn infix_chain_tests() {
        let a = Ast::var("a");
        let b = Ast::var("b");
        let c = Ast::var("c");
        let a_plus_b = Ast::infix(a.clone(), "+", b.clone());
        let a_plus_b_plus_c = Ast::infix(a_plus_b, "+", c.clone());
        let chain = Chain::try_new(&a_plus_b_plus_c).unwrap();
        expect_at(&chain.target, &a);
        expect_at(&chain.args[0].operand, &b);
        expect_at(&chain.args[1].operand, &c);

        test_enumerating(&chain, &a_plus_b_plus_c, &[&a, &b, &c]);
    }

    #[test]
    fn infix_chain_tests_right() {
        let a = Ast::var("a");
        let b = Ast::var("b");
        let c = Ast::var("c");
        let b_comma_c = Ast::infix(b.clone(), ",", c.clone());
        let a_comma_b_comma_c = Ast::infix(a.clone(), ",", b_comma_c);
        let chain = Chain::try_new(&a_comma_b_comma_c).unwrap();
        expect_at(&chain.target, &c);
        expect_at(&chain.args[0].operand, &b);
        expect_at(&chain.args[1].operand, &a);
    }

    #[test]
    fn assignment_opr_test() {
        let opr = assignment();
        assert_eq!(opr.name, "=");
        assert_eq!(opr.repr(), "=");
    }

    // TODO[ao] add tests for modifying chain.
}
