package org.enso.text.editing

import TestData.testSnippet
import org.enso.text.editing.model.{Position, Range, TextEdit}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class EditorOpsSpec extends AnyFlatSpec with Matchers with EitherValues {

  "An editor" should "be able to apply multiple diffs" in {
    //given
    val signaturePosition = Range(Position(2, 12), Position(2, 13))
    val signatureDiff     = TextEdit(signaturePosition, "arg")
    val bodyPosition      = Range(Position(2, 23), Position(2, 24))
    val bodyDiff          = TextEdit(bodyPosition, "arg")
    val diffs             = List(signatureDiff, bodyDiff)
    //when
    val result = EditorOps.applyEdits(testSnippet, diffs)
    //then
    result.map(_.toString) mustBe Right("""
                                          |main =
                                          |    apply = arg f -> f arg
                                          |    adder = a b -> a + b
                                          |    plusOne = apply (f = adder 1)
                                          |    result = plusOne 10
                                          |    result""".stripMargin)
  }

  it should "take into account applied so far edits when validate next diff" in {
    //given
    val firstBlock = Range(Position(2, 0), Position(5, 0))
    val diff1      = TextEdit(firstBlock, "foo")
    val nonExistingPositionAfterFirstEdit =
      Range(Position(5, 4), Position(5, 10))
    val diff2 = TextEdit(nonExistingPositionAfterFirstEdit, "bar")
    val diffs = List(diff1, diff2)
    //when
    val result = EditorOps.applyEdits(testSnippet, diffs)
    //then
    result mustBe Left(InvalidPosition(Position(5, 4)))
  }

}
