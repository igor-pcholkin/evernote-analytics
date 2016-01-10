
package evernoteanalytics

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import java.util.Random

class BasicSimulation extends Simulation {

  val httpConf = http
    .baseURL("http://localhost:9000")
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8") // Here are the common headers
    //.headers(token)
    .doNotTrackHeader("1")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .acceptEncodingHeader("gzip, deflate")
    .userAgentHeader("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:16.0) Gecko/20100101 Firefox/16.0")

  val requests = Seq(

    "search_Scala" -> "/notes?textInside=Scala"
    )

  val scn0 = scenario("Test all routes of resource service")

  val scn = requests.foldLeft(scn0) {
    case (scn, (requestName, requestURL)) =>
      scn.exec(addCookie(Cookie("PLAY_SESSION", "804f1fdf2b0a8c039e7a9b1484af6648861e6c2e-token=S%3Ds258%3AU%3D20069ef%3AE%3D1597db2817b%3AC%3D152260152d8%3AP%3D185%3AA%3Dpchelka123%3AV%3D2%3AH%3D6ddcf7c79a8e93ee319c4ba11c319226&secret=&noteStoreUrl=https%3A%2F%2Fwww.evernote.com%2Fshard%2Fs258%2Fnotestore&notebooks=%D0%91%D0%BB%D0%BE%D0%BA%D0%BD%D0%BE%D1%82+%D0%BF%D0%BE%D0%BB%D1%8C%D0%B7%D0%BE%D0%B2%D0%B0%D1%82%D0%B5%D0%BB%D1%8F+pchelka123%2CLearning%2CApache+Hadoop%2CWork%2CHome%2C%D0%9F%D0%B5%D0%BD%D0%B8%D0%B5%2C%D0%A7%D1%82%D0%B5%D0%BD%D0%B8%D0%B5%2COther+Ideas%2C%D0%9F%D0%BE%D1%81%D0%BC%D0%BE%D1%82%D1%80%D0%B5%D1%82%D1%8C%2CRaspberryPi%2CSemantic+Web%2CBigData%2CProjects%2CCoding+proficiency%2C%D0%A4%D0%B8%D0%BB%D1%8C%D0%BC%D1%8B%2C%D0%94%D0%B5%D0%BB%D0%B0+-+%D0%B4%D0%BD%D0%B5%D0%B2%D0%BD%D0%B8%D0%BA%2C%D0%94%D0%BD%D0%B5%D0%B2%D0%BD%D0%B8%D0%BA%2CMath+hacks%2C%D0%9F%D0%BB%D0%B0%D0%BD%D1%8B%2C%D0%9A%D0%BE%D0%BD%D1%86%D0%B5%D0%BF%D1%86%D0%B8%D0%B8+%D0%B4%D0%BB%D1%8F+%D0%B8%D0%B7%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D1%8F-%D0%BF%D0%BE%D0%B2%D1%82%D0%BE%D1%80%D0%B5%D0%BD%D0%B8%D1%8F%2CRecognizing+code+patterns+in+Scala%2C%D0%9B%D1%83%D1%87%D1%88%D0%B5%D0%B5+%D0%BA%D0%B8%D0%BD%D0%BE%2C%D0%A8%D1%83%D1%82%D0%BA%D0%B8")))
      .exec(http(requestName).get(requestURL)).pause(1)
  }

  // run all performance tests with 1 concurrent users
  setUp(scn.inject(atOnceUsers(1)).protocols(httpConf))

}
