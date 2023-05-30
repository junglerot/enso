// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
#![allow(clippy::bool_to_int_with_if)]
#![allow(clippy::let_and_return)]

use ast::crumbs::*;
use enso_prelude::*;
use enso_text::traits::*;
use span_tree::*;
use wasm_bindgen::prelude::*;

use enso_web as web;
use span_tree::node;
use span_tree::node::InsertionPointType;
use uuid::Uuid;



#[entry_point(span_tree)]
#[allow(dead_code)]
pub fn main() {
    let pattern_cr = vec![Seq { right: false }, Or, Or, Build];
    let val = ast::crumbs::SegmentMatchCrumb::Body { val: pattern_cr };
    let parens_cr1 = ast::crumbs::MatchCrumb::Segs { val: val.clone(), index: 0 };
    let parens_cr = ast::crumbs::MatchCrumb::Segs { val, index: 0 };
    let _input_span_tree = builder::TreeBuilder::new(36)
        .add_child(0, 14, node::Kind::chained(), PrefixCrumb::Func)
        .add_child(0, 9, node::Kind::Operation, PrefixCrumb::Func)
        .set_ast_id(Uuid::new_v4())
        .done()
        .add_empty_child(10, InsertionPointType::BeforeArgument(0))
        .add_child(10, 4, node::Kind::this().removable(), PrefixCrumb::Arg)
        .set_ast_id(Uuid::new_v4())
        .done()
        .add_empty_child(14, InsertionPointType::Append)
        .set_ast_id(Uuid::new_v4())
        .done()
        .add_child(15, 21, node::Kind::argument().removable(), PrefixCrumb::Arg)
        .set_ast_id(Uuid::new_v4())
        .add_child(1, 19, node::Kind::argument(), parens_cr1)
        .set_ast_id(Uuid::new_v4())
        .add_child(0, 12, node::Kind::Operation, PrefixCrumb::Func)
        .set_ast_id(Uuid::new_v4())
        .done()
        .add_empty_child(13, InsertionPointType::BeforeArgument(0))
        .add_child(13, 6, node::Kind::this(), PrefixCrumb::Arg)
        .set_ast_id(Uuid::new_v4())
        .done()
        .add_empty_child(19, InsertionPointType::Append)
        .done()
        .done()
        .add_empty_child(36, InsertionPointType::Append)
        .build();

    let input_span_tree2 = Node::<()>::new()
        .new_child(|t| {
            t.new_ast_id()
                .kind(node::Kind::chained())
                .crumbs(PrefixCrumb::Func)
                .new_child(|t| {
                    t.size(9.bytes())
                        .kind(node::Kind::Operation)
                        .crumbs(PrefixCrumb::Func)
                        .new_ast_id()
                })
                .new_child(|t| t.size(1.bytes()))
                .new_child(|t| {
                    t.size(4.bytes())
                        .kind(node::Kind::this().removable())
                        .crumbs(PrefixCrumb::Arg)
                        .new_ast_id()
                })
                .new_child(|t| t.size(1.bytes()))
        })
        .new_child(|t| {
            t.new_ast_id()
                .kind(node::Kind::argument().removable())
                .crumbs(PrefixCrumb::Arg)
                .new_child(|t| {
                    t.new_ast_id()
                        .offset(1.bytes())
                        .kind(node::Kind::argument().removable())
                        .crumbs(parens_cr)
                        .new_child(|t| {
                            t.size(12.bytes())
                                .kind(node::Kind::Operation)
                                .crumbs(PrefixCrumb::Func)
                                .new_ast_id()
                        })
                        .new_child(|t| t.size(1.bytes()))
                        .new_child(|t| {
                            t.size(6.bytes())
                                .kind(node::Kind::this().removable())
                                .crumbs(PrefixCrumb::Arg)
                                .new_ast_id()
                        })
                        .new_child(|t| t.size(1.bytes()))
                })
        })
        .new_child(|t| t.size(1.bytes()));

    debug!("{input_span_tree2:#?}");
}
