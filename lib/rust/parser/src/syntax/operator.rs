//! Operator related functionalities.

use crate::prelude::*;

use crate::syntax;
use crate::syntax::token;
use crate::syntax::token::Token;



// ==================
// === Precedence ===
// ==================

/// Operator precedence resolver.
#[derive(Debug)]
pub struct Precedence<'s> {
    nospace_builder: ExpressionBuilder<'s>,
    builder:         ExpressionBuilder<'s>,
}

impl<'s> Default for Precedence<'s> {
    fn default() -> Self {
        Self::new()
    }
}

impl<'s> Precedence<'s> {
    /// Return a new operator precedence resolver.
    pub fn new() -> Self {
        Self {
            nospace_builder: ExpressionBuilder { nospace: true, ..default() },
            builder:         ExpressionBuilder { nospace: false, ..default() },
        }
    }

    /// Resolve precedence in a context where the result cannot be an operator section or template
    /// function.
    pub fn resolve_non_section(
        &mut self,
        items: impl IntoIterator<Item = syntax::Item<'s>>,
    ) -> Option<syntax::Tree<'s>> {
        items.into_iter().for_each(|i| self.push(i));
        self.finish_().map(|op| op.value)
    }

    /// Resolve precedence.
    pub fn resolve(
        &mut self,
        items: impl IntoIterator<Item = syntax::Item<'s>>,
    ) -> Option<syntax::Tree<'s>> {
        items.into_iter().for_each(|i| self.push(i));
        self.finish()
    }

    /// Extend the expression with a token.
    pub fn push(&mut self, item: syntax::Item<'s>) {
        if starts_new_no_space_group(&item) {
            self.builder.extend_from(&mut self.nospace_builder);
        }
        match item {
            syntax::Item::Token(Token {
                variant: token::Variant::Operator(opr),
                left_offset,
                code,
            }) => self.nospace_builder.operator(Token(left_offset, code, opr)),
            syntax::Item::Token(token) =>
                self.nospace_builder.operand(syntax::Tree::from(token).into()),
            syntax::Item::Tree(tree) => self.nospace_builder.operand(tree.into()),
            syntax::Item::Block(_) => self.nospace_builder.operand(item.to_ast().into()),
        }
    }

    fn finish_(&mut self) -> Option<Operand<syntax::Tree<'s>>> {
        self.builder.extend_from(&mut self.nospace_builder);
        self.builder.finish()
    }

    /// Return the result.
    pub fn finish(&mut self) -> Option<syntax::Tree<'s>> {
        self.finish_().map(syntax::Tree::from)
    }
}

impl<'s> Extend<syntax::Item<'s>> for Precedence<'s> {
    fn extend<T: IntoIterator<Item = syntax::Item<'s>>>(&mut self, iter: T) {
        for token in iter {
            self.push(token);
        }
    }
}

/// Take [`Item`] stream, resolve operator precedence and return the final AST.
///
/// The precedence resolution algorithm is based on the Shunting yard algorithm[1], extended to
/// handle operator sections.
/// [1]: https://en.wikipedia.org/wiki/Shunting_yard_algorithm
pub fn resolve_operator_precedence(items: NonEmptyVec<syntax::Item<'_>>) -> syntax::Tree<'_> {
    resolve_operator_precedence_if_non_empty(items).unwrap()
}

/// If the input sequence is non-empty, return the result of applying
/// [`resolve_operator_precedence`] to it.
pub fn resolve_operator_precedence_if_non_empty<'s>(
    items: impl IntoIterator<Item = syntax::Item<'s>>,
) -> Option<syntax::Tree<'s>> {
    let mut precedence = Precedence::new();
    precedence.resolve(items)
}

// Returns `true` for an item if that item should not follow any other item in a no-space group
// (i.e. the item has "space" before it).
fn starts_new_no_space_group(item: &syntax::item::Item) -> bool {
    if item.left_visible_offset().width_in_spaces != 0 {
        return true;
    }
    if let syntax::item::Item::Block(_) = item {
        return true;
    }
    if let syntax::item::Item::Token(Token { variant: token::Variant::Operator(opr), .. }) = item
            && opr.properties.is_sequence() {
        return true;
    }
    false
}


// === Expression builder ===

/// Stack machine that builds an expression from syntax nodes.
///
/// The operator-precedence algorithm[1] used is based on the shunting yard algorithm[2], extended
/// to support *operator sections*, function application, and unary operators, and correctly report
/// errors relating to consecutive operators.
///
/// [^1](https://en.wikipedia.org/wiki/Operator-precedence_parser)
/// [^2](https://en.wikipedia.org/wiki/Shunting_yard_algorithm)
#[derive(Default, Debug, PartialEq, Eq)]
struct ExpressionBuilder<'s> {
    output:         Vec<Operand<syntax::Tree<'s>>>,
    operator_stack: Vec<Operator<'s>>,
    prev_type:      Option<ItemType>,
    nospace:        bool,
}

impl<'s> ExpressionBuilder<'s> {
    /// Extend the expression with an operand.
    pub fn operand(&mut self, operand: Operand<syntax::Tree<'s>>) {
        if self.prev_type == Some(ItemType::Ast) {
            if let Some(Operand { value: syntax::Tree { variant: box
                    syntax::tree::Variant::TextLiteral(ref mut lhs), .. }, .. }) = self.output.last_mut()
                    && !lhs.closed
                    && let box syntax::tree::Variant::TextLiteral(mut rhs) = operand.value.variant {
                syntax::tree::join_text_literals(lhs, &mut rhs, operand.value.span);
                if let syntax::tree::TextLiteral { open: Some(open), newline: None, elements, closed: true, close: None } = lhs
                    && open.code.starts_with('#') {
                    let elements = mem::take(elements);
                    let mut open = open.clone();
                    let lhs_tree = self.output.pop().unwrap().value;
                    open.left_offset += lhs_tree.span.left_offset;
                    let doc = syntax::tree::DocComment { open, elements, newlines: default() };
                    self.output.push(syntax::Tree::documented(doc, default()).into());
                }
                return;
            }
            self.application();
        }
        self.output.push(operand);
        self.prev_type = Some(ItemType::Ast);
    }

    fn application(&mut self) {
        let precedence = token::Precedence::application();
        let associativity = token::Associativity::Left;
        let arity = Arity::Binary {
            tokens:                  default(),
            lhs_section_termination: default(),
        };
        self.push_operator(precedence, associativity, arity);
    }

    /// Extend the expression with an operator.
    pub fn operator(&mut self, opr: token::Operator<'s>) {
        use ItemType::*;
        let assoc = opr.properties.associativity();
        match (
            self.nospace,
            opr.properties.binary_infix_precedence(),
            opr.properties.unary_prefix_precedence(),
        ) {
            // If an operator has a binary role, and a LHS is available, it's acting as binary.
            (_, Some(prec), _) if self.prev_type == Some(Ast) =>
                self.binary_operator(prec, assoc, opr),
            // Otherwise, if the operator is inside a nospace group, and it has a unary role,
            // it's acting as unary.
            (true, _, Some(prec)) => self.unary_operator(prec, assoc, Unary::Simple(opr)),
            // Outside of a nospace group, a unary-only operator is missing an operand.
            (false, None, Some(_)) =>
                self.operand(syntax::tree::apply_unary_operator(opr, None).into()),
            // Binary operator section (no LHS).
            (_, Some(prec), _) => self.binary_operator(prec, assoc, opr),
            // Failed to compute a role for the operator; this should not be possible.
            (_, None, None) => unreachable!(),
        }
    }

    fn unary_operator(
        &mut self,
        prec: token::Precedence,
        assoc: token::Associativity,
        arity: Unary<'s>,
    ) {
        if self.prev_type == Some(ItemType::Ast) {
            self.application();
        }
        self.push_operator(prec, assoc, Arity::Unary(arity));
    }

    /// Extend the expression with a binary operator, by pushing it to the `operator_stack` or
    /// emitting a multiple-operator error.
    fn binary_operator(
        &mut self,
        prec: token::Precedence,
        assoc: token::Associativity,
        opr: token::Operator<'s>,
    ) {
        if self.prev_type == Some(ItemType::Opr)
                && let Some(prev_opr) = self.operator_stack.last_mut()
                && let Arity::Binary { tokens, .. } = &mut prev_opr.opr {
            if tokens.len() == 1 && tokens[0].properties.is_dot() {
                let Token { left_offset, code, .. } = opr;
                let is_operator = true;
                let opr_ident = token::ident(left_offset, code, default(), default(), default(), is_operator, default());
                self.output.push(Operand::from(syntax::Tree::ident(opr_ident)));
                self.prev_type = Some(ItemType::Ast);
                return;
            }
            tokens.push(opr);
            return;
        }
        self.push_operator(prec, assoc, Arity::binary(opr));
    }

    /// Add an operator to the stack; [`reduce`] the stack first, as appropriate for the specified
    /// precedence.
    fn push_operator(
        &mut self,
        precedence: token::Precedence,
        associativity: token::Associativity,
        opr: Arity<'s>,
    ) {
        let opr = Operator { precedence, associativity, opr };
        if self.prev_type != Some(ItemType::Opr) {
            // If the previous item was also an operator, this must be a unary operator following a
            // binary operator; we cannot reduce the stack because the unary operator must be
            // evaluated before the binary operator, regardless of precedence.
            let mut rhs = self.output.pop();
            self.reduce(precedence, &mut rhs);
            if let Some(rhs) = rhs {
                self.output.push(rhs);
            }
        }
        self.operator_stack.push(opr);
        self.prev_type = Some(ItemType::Opr);
    }

    /// Given a starting value, replace it with the result of successively applying to it all
    /// operators in the `operator_stack` that have precedence greater than or equal to the
    /// specified value, consuming LHS values from the `output` stack as needed.
    fn reduce(&mut self, prec: token::Precedence, rhs: &mut Option<Operand<syntax::Tree<'s>>>) {
        while let Some(opr) = self.operator_stack.pop_if(|opr| {
            opr.precedence > prec
                || (opr.precedence == prec && opr.associativity == token::Associativity::Left)
        }) {
            let rhs_ = rhs.take();
            let ast = match opr.opr {
                Arity::Unary(Unary::Simple(opr)) =>
                    Operand::from(rhs_).map(|item| syntax::tree::apply_unary_operator(opr, item)),
                Arity::Unary(Unary::Fragment { mut fragment }) => {
                    if let Some(rhs_) = rhs_ {
                        fragment.operand(rhs_);
                    }
                    fragment.finish().unwrap()
                }
                Arity::Binary { tokens, lhs_section_termination } => {
                    let lhs = self.output.pop();
                    if let Some(lhs_termination) = lhs_section_termination {
                        let lhs = match lhs_termination {
                            SectionTermination::Reify => lhs.map(syntax::Tree::from),
                            SectionTermination::Unwrap => lhs.map(|op| op.value),
                        };
                        let rhs = rhs_.map(syntax::Tree::from);
                        let ast = syntax::tree::apply_operator(lhs, tokens, rhs, self.nospace);
                        Operand::from(ast)
                    } else {
                        let rhs = rhs_.map(syntax::Tree::from);
                        let mut elided = 0;
                        if tokens.len() != 1 || tokens[0].properties.can_form_section() {
                            elided += lhs.is_none() as u32 + rhs.is_none() as u32;
                        }
                        let mut operand = Operand::from(lhs).map(|lhs| {
                            syntax::tree::apply_operator(lhs, tokens, rhs, self.nospace)
                        });
                        operand.elided += elided;
                        operand
                    }
                }
            };
            *rhs = Some(ast);
        }
    }

    /// Return an expression constructed from the accumulated state. Will return `None` only if no
    /// inputs were provided. `self` will be reset to its initial state.
    pub fn finish(&mut self) -> Option<Operand<syntax::Tree<'s>>> {
        use ItemType::*;
        let mut out = (self.prev_type == Some(Ast)).and_option_from(|| self.output.pop());
        self.reduce(token::Precedence::min(), &mut out);
        debug_assert!(self.operator_stack.is_empty());
        debug_assert_eq!(
            &self.output,
            &[],
            "Internal error. Not all tokens were consumed while constructing the expression."
        );
        self.prev_type = None;
        out
    }

    pub fn extend_from(&mut self, child: &mut Self) {
        if child.output.is_empty() && let Some(op) = child.operator_stack.pop() {
            match op.opr {
                Arity::Unary(Unary::Simple(un)) => self.operator(un),
                Arity::Unary(Unary::Fragment{ .. }) => unreachable!(),
                Arity::Binary { tokens, .. } => tokens.into_iter().for_each(|op| self.operator(op)),
            };
            child.prev_type = None;
            debug_assert_eq!(&child.operator_stack, &[]);
            return;
        }
        if child.prev_type == Some(ItemType::Opr)
                && let Arity::Binary { tokens, .. } = &child.operator_stack.last().unwrap().opr
                && let Some(token) = tokens.last()
                && token.properties.is_arrow() {
            let precedence = token::Precedence::min_valid();
            let associativity = token::Associativity::Right;
            let fragment = ExpressionBuilder {
                output:         mem::take(&mut child.output),
                operator_stack: mem::take(&mut child.operator_stack),
                prev_type:      mem::take(&mut child.prev_type),
                nospace:        child.nospace,
            };
            let arity = Unary::Fragment { fragment };
            self.unary_operator(precedence, associativity, arity);
            return;
        }
        if let Some(o) = child.finish() {
            self.operand(o);
        }
    }
}

/// Classify an item as an operator, or operand; this is used in [`resolve_operator_precedence`] to
/// merge consecutive nodes of the same type.
#[derive(PartialEq, Eq, Debug)]
enum ItemType {
    Ast,
    Opr,
}

/// An operator, whose arity and precedence have been determined.
#[derive(Debug, PartialEq, Eq)]
struct Operator<'s> {
    precedence:    token::Precedence,
    associativity: token::Associativity,
    opr:           Arity<'s>,
}

/// Classifies the role of an operator.
#[derive(Debug, PartialEq, Eq)]
enum Arity<'s> {
    Unary(Unary<'s>),
    Binary {
        tokens:                  Vec<token::Operator<'s>>,
        lhs_section_termination: Option<SectionTermination>,
    },
}

impl<'s> Arity<'s> {
    fn binary(tok: token::Operator<'s>) -> Self {
        let lhs_section_termination = tok.properties.lhs_section_termination();
        let tokens = vec![tok];
        Self::Binary { tokens, lhs_section_termination }
    }

    fn unary(tok: token::Operator<'s>) -> Self {
        Self::Unary(Unary::Simple(tok))
    }
}

#[derive(Debug, PartialEq, Eq)]
enum Unary<'s> {
    Simple(token::Operator<'s>),
    Fragment { fragment: ExpressionBuilder<'s> },
}


// === Operand ===

/// Wraps a value, tracking the number of wildcards or elided operands within it.
#[derive(Default, Debug, PartialEq, Eq)]
struct Operand<T> {
    value:     T,
    /// Number of elided operands in the subtree, potentially forming an *operator section*.
    elided:    u32,
    /// Number of wildcards in the subtree, potentially forming a *template function*.
    wildcards: u32,
}

/// Transpose.
impl<T> From<Option<Operand<T>>> for Operand<Option<T>> {
    fn from(operand: Option<Operand<T>>) -> Self {
        match operand {
            Some(Operand { value, elided, wildcards }) =>
                Self { value: Some(value), elided, wildcards },
            None => default(),
        }
    }
}

/// Unit. Creates an Operand from a node.
impl<'s> From<syntax::Tree<'s>> for Operand<syntax::Tree<'s>> {
    fn from(mut value: syntax::Tree<'s>) -> Self {
        let elided = 0;
        let wildcards = if let syntax::Tree {
            variant:
                box syntax::tree::Variant::Wildcard(syntax::tree::Wildcard { de_bruijn_index, .. }),
            ..
        } = &mut value
        {
            debug_assert_eq!(*de_bruijn_index, None);
            *de_bruijn_index = Some(0);
            1
        } else {
            0
        };
        Self { value, wildcards, elided }
    }
}

/// Counit. Bakes any information about elided operands into the tree.
impl<'s> From<Operand<syntax::Tree<'s>>> for syntax::Tree<'s> {
    fn from(operand: Operand<syntax::Tree<'s>>) -> Self {
        let Operand { mut value, elided, wildcards } = operand;
        if elided != 0 {
            value = syntax::Tree::opr_section_boundary(elided, value);
        }
        if wildcards != 0 {
            value = syntax::Tree::template_function(wildcards, value);
        }
        value
    }
}

impl<T> Operand<T> {
    /// Operate on the contained value without altering the elided-operand information.
    fn map<U>(self, f: impl FnOnce(T) -> U) -> Operand<U> {
        let Self { value, elided, wildcards } = self;
        let value = f(value);
        Operand { value, elided, wildcards }
    }
}


// === SectionTermination ===

/// Operator-section/template-function termination behavior of an operator with regard to an
/// operand.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum SectionTermination {
    /// If the operand is an operator-section/template-function, indicate it by wrapping it in a
    /// suitable node.
    Reify,
    /// Discard any operator-section/template-function properties associated with the operand.
    Unwrap,
}

impl Default for SectionTermination {
    fn default() -> Self {
        Self::Reify
    }
}
