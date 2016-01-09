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

object Application extends Controller {

  val mode = "www"
  val USER_STORE_URL = s"https://$mode.evernote.com/edam/user"

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
          val userStoreTrans = new THttpClient(USER_STORE_URL)
          val userStoreProt: TBinaryProtocol = new TBinaryProtocol(userStoreTrans)
          val userStore: UserStore.Client = new UserStore.Client(userStoreProt, userStoreProt)
          val noteStoreUrl: String = userStore.getNoteStoreUrl(token)
          val noteStoreTrans: THttpClient = new THttpClient(noteStoreUrl)
          val noteStoreProt: TBinaryProtocol = new TBinaryProtocol(noteStoreTrans)
          val noteStore: NoteStore.Client = new NoteStore.Client(noteStoreProt, noteStoreProt)
          Future {
            searchNotes(title, searchByIndentation, textInside, ps)(noteStore, token)
          }
        case None => throw new RuntimeException("token not found")
      }

      val istream = new PipedInputStream(pipedOutputStream)
      Ok.chunked(Enumerator.fromStream(istream).andThen(Enumerator.eof)).as("text/html")
      //Ok(notes.toString())
    }

  /**
   * Search a user's notes and display the results.
   */
  def searchNotes(title: Option[String], searchByIndentation: Boolean, textInside: Option[String], ps: PrintStream)(implicit noteStore: NoteStore.Client, token: String) = {

    @tailrec
    def goFindNotes(offset: Int, startMonthAggregation: (String, Int))(implicit filter: NoteFilter): (String, Int) = {
      val noteList = noteStore.findNotes(token, filter, offset, 50);
      val noteChunk = noteList.getNotes
      val totalNoteCount = noteList.getTotalNotes()

      println(s"Found $totalNoteCount matching notes");
      println(s"Current chunk offset: $offset, length: " + noteChunk.length)
      println("Last note: " + getMonth(noteChunk.last))

      val endNoteAggregation = aggregateByMonthAndPrintCounts(noteChunk, startMonthAggregation, searchByIndentation, textInside, ps)

      val newOffset = offset + noteChunk.length
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
    val endNoteAggregation = goFindNotes(0, (startFakeMonth, 0))

    ps.println(endNoteAggregation)
    ps.close
  }

  def aggregateByMonthAndPrintCounts(notes: java.util.List[Note], startMonthAggregation: (String, Int), searchByIndentation: Boolean,
                                     textInside: Option[String], ps: PrintStream)(implicit noteStore: NoteStore.Client, token: String) = {
    val endNoteAggregation = notes.foldLeft(startMonthAggregation) {
      case ((currentMonth, currentMonthCount), note) =>
        val noteMonth = getMonth(note)
        val noteFeatureCount = getFeatureCountByNote(note, searchByIndentation, textInside)

        if (noteFeatureCount == 0) {
          println("Note with no match!")
          println(note.getContent())
        }

        if (currentMonth == noteMonth)
          (currentMonth, currentMonthCount + noteFeatureCount)
        else {
          if (currentMonth != startFakeMonth) {
            ps.println(currentMonth -> currentMonthCount)
          }
          (noteMonth, noteFeatureCount)
        }
    }
    endNoteAggregation
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
      println(s"Matched: ${m.group()}")
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
