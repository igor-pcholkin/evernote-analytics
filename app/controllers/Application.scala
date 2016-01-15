package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import views._
import java.text.DateFormat
import com.evernote.edam.`type`.Note
import com.evernote.edam.notestore.NoteFilter
import com.evernote.edam.notestore.NoteList
import java.util.Calendar
import java.util.regex.Pattern
import java.util.regex.Matcher
import com.evernote.edam.`type`.NoteSortOrder
import com.evernote.thrift.protocol.TBinaryProtocol
import com.evernote.edam.notestore.NoteStore
import com.evernote.thrift.transport.THttpClient
import com.evernote.edam.userstore.UserStore
import scala.collection.JavaConversions._
import java.text.SimpleDateFormat
import scala.language.experimental.macros
import java.io.InputStream
import play.api.libs.iteratee.Enumerator
import java.io.ByteArrayInputStream
import scala.concurrent.ExecutionContext.Implicits.global
import java.io.PipedOutputStream
import java.io.PipedInputStream
import java.io.DataOutputStream
import java.io.PrintStream
import scala.concurrent.Future
import annotation.tailrec
import com.evernote.edam.notestore.NotesMetadataResultSpec

object Application extends Controller {

  val startFakeMonth = "1/1900"

  /**
   * Home page
   */
  def index = Action { implicit request =>
    Ok(html.index()).withSession(request.session)
  }

  /**
   * Clear session
   */
  def clear = Action { implicit request =>
    Ok(html.index()).withNewSession
  }

  def notes(title: Option[String] = None, searchByIndentation: Boolean, textInside: Option[String] = None) =
    Action { implicit request =>

      val pipedOutputStream = new PipedOutputStream()
      val ps = new PrintStream(pipedOutputStream)
      request.session.get("token") match {
        case Some(token) =>
          println(s"token: $token")
          val (noteStore, noteStoreUrl) = Evernote.createNoteStoreClient(token)
          Future {
            searchNotes(title, searchByIndentation, textInside, ps)(noteStore, token)
          }
        case None => throw new RuntimeException("token not found")
      }

      val istream = new PipedInputStream(pipedOutputStream)
      Ok.chunked(Enumerator.fromStream(istream).andThen(Enumerator.eof)).as("text/html")
    }

  /**
   * Search a user's notes and display the results.
   */
  def searchNotes(title: Option[String], searchByIndentation: Boolean, textInside: Option[String], ps: PrintStream)(implicit noteStore: NoteStore.Client, token: String) = {

    @tailrec
    def goFindNotes(offset: Int, startMonthAggregation: (String, Int, Int))(implicit filter: NoteFilter): (String, Int, Int) = {
      val noteList = noteStore.findNotes(token, filter, offset, 50);
      val noteChunk = noteList.getNotes
      val totalNoteCount = noteList.getTotalNotes()

      println(s"Found $totalNoteCount matching notes");
      println(s"Current chunk offset: $offset, length: " + noteChunk.length)
      println(s"Searched words: ${noteList.getSearchedWordsSize}")
      println(s"startMonthAggregation: $startMonthAggregation")

      val endNoteAggregation = aggregateByMonthAndPrintCounts(noteChunk, startMonthAggregation, searchByIndentation, textInside, ps)

      val newOffset = offset + noteChunk.length

      println(s"newOffset: $newOffset, totalNoteCount: $totalNoteCount")

      if (newOffset < totalNoteCount)
        goFindNotes(newOffset, endNoteAggregation)
      else
        endNoteAggregation
    }

    val titleFilter = title match {
      case Some(t) => s"intitle:$t "
      case None    => ""
    }
    val textInsideFilter = textInside match {
      case Some(t) => s"$t"
      case None    => ""
    }
    val query = s"$titleFilter $textInsideFilter"

    implicit val filter = new NoteFilter();
    filter.setWords(query);
    filter.setOrder(NoteSortOrder.CREATED.getValue());
    filter.setAscending(true);

    println("Searching for notes matching query: " + query);
    val endNoteAggregation = goFindNotes(0, (startFakeMonth, 0, 0))

    println(s"End note aggregation: $endNoteAggregation")

    outputAggregation(ps, endNoteAggregation, true)
  }

  def outputAggregation(ps: PrintStream, aggregation: (String, Int, Int), finish: Boolean = false): Boolean = {
    println(s"Output aggregation: $aggregation")
    val (month, count, outputIndex) = aggregation
    val outputPerformed = if (count != 0) {
      ps.println(s"#$outputIndex: $month:$count")
      true
    } else {
      false
    }
    if (finish) {
      ps.close
    }
    outputPerformed
  }

  def aggregateByMonthAndPrintCounts(notes: java.util.List[Note], startMonthAggregation: (String, Int, Int), searchByIndentation: Boolean,
                                     textInside: Option[String], ps: PrintStream)(implicit noteStore: NoteStore.Client, token: String) = {
    val endNoteAggregation = notes.foldLeft(startMonthAggregation) {
      case ((currentMonth, currentMonthCount, outputIndex), note) =>
        val noteMonth = getMonth(note)
        val noteFeatureCount = getFeatureCountByNote(note, searchByIndentation, textInside)

        if (currentMonth == noteMonth)
          (currentMonth, currentMonthCount + noteFeatureCount, outputIndex)
        else {
          val outputPerformed = if (currentMonth != startFakeMonth) {
            outputAggregation(ps, (currentMonth, currentMonthCount, outputIndex))
          } else {
            false
          }
          startAggregationForNewMonth((noteMonth, noteFeatureCount, outputIndex), outputPerformed)
        }
    }
    endNoteAggregation
  }

  def startAggregationForNewMonth(startNote: (String, Int, Int), outputPerformed: Boolean) = {
    println(s"Start aggregation: $startNote")
    val newOutputIndex = if (outputPerformed) {
      startNote._3 + 1
    } else {
      startNote._3
    }
    (startNote._1, startNote._2, newOutputIndex)
  }

  def getMonth(note: Note) = {
    val createdTs = note.getCreated
    val c = Calendar.getInstance
    c.setTimeInMillis(createdTs)
    (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.YEAR)
  }

  def getFeatureCountByNote(note: Note, searchByIndentation: Boolean, textInside: Option[String])(implicit noteStore: NoteStore.Client, token: String): Int = {
    @tailrec
    def goMatcher(m: Matcher, count: Int): Int = if (m.find) {
      goMatcher(m, count + 1)
    } else count

    val textInsideRegex = textInside match {
      case Some(text) => s".*$text.*"
      case None       => ".*"
    }

    if (searchByIndentation || textInside != None) {
      val fullNote = noteStore.getNote(token, note.getGuid(), true, true,
        false, false);
      val noteText = fullNote.getContent();

      val patternText =
        if (searchByIndentation)
          s"<div>\\d+\\s?\\.|<li>${textInsideRegex}</div>|</li>\\n"
        else
          s"${textInsideRegex}"

      val p = Pattern.compile(patternText);
      val m = p.matcher(noteText);
      goMatcher(m, 0)
    } else 1
  }

}
