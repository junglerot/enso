package org.enso.languageserver.filemanager

import java.nio.file.{Files, Paths}

import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source
import java.nio.file.Path

class FileSystemSpec extends AnyFlatSpec with Matchers {

  "A file system interpreter" should "write textual content to file" in new TestCtx {
    //given
    val path    = Paths.get(testDirPath.toString, "foo.txt")
    val content = "123456789"
    //when
    val result =
      objectUnderTest.write(path.toFile, content).unsafeRunSync()
    //then
    result shouldBe Right(())
    readTxtFile(path) shouldBe content
  }

  it should "overwrite existing files" in new TestCtx {
    //given
    val path            = Paths.get(testDirPath.toString, "foo.txt")
    val existingContent = "123456789"
    val newContent      = "abcdef"
    //when
    objectUnderTest.write(path.toFile, existingContent).unsafeRunSync()
    objectUnderTest.write(path.toFile, newContent).unsafeRunSync()
    //then
    readTxtFile(path) shouldBe newContent
  }

  it should "create the parent directory if it doesn't exist" in new TestCtx {
    //given
    val path    = Paths.get(testDirPath.toString, "foo.txt")
    val content = "123456789"
    testDir.delete()
    //when
    val result =
      objectUnderTest.write(path.toFile, content).unsafeRunSync()
    //then
    result shouldBe Right(())
    readTxtFile(path) shouldBe content
  }

  def readTxtFile(path: Path): String = {
    val buffer  = Source.fromFile(path.toFile)
    val content = buffer.getLines().mkString
    buffer.close()
    content
  }

  trait TestCtx {

    val testDirPath = Files.createTempDirectory(null)

    val testDir = testDirPath.toFile
    testDir.deleteOnExit()

    val objectUnderTest = new FileSystem[IO]

  }

}
