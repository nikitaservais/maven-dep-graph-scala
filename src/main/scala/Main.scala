import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.util.ByteString
import java.nio.file.Paths
import concurrent.duration.DurationInt

val MAX_SUBSTREAMS = 185
val GroupsPerSecond = 10 // Throttle rate

case class MavenDependency(library: Library, dependency: Dependency, dependencyType: DependencyType)

case class Library(GroupId: String, ArtifactId: String, Version: String)

case class Dependency(GroupId: String, ArtifactId: String, Version: String);

enum DependencyType {
  case COMPILE, RUNTIME
}

object Main extends App {
  implicit val system: ActorSystem = ActorSystem("QuickStart")

  val source = FileIO.fromPath(Paths.get("maven_dependencies.csv"))

  val parseFile = Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true)
    .map(_.utf8String)
    .drop(1)

  val instantiateClasses = Flow[String].map(parseLine)

  val groupByLibraryName = Flow[MavenDependency].groupBy(MAX_SUBSTREAMS, _.library).mergeSubstreams

  val throttle = Flow[MavenDependency].throttle(GroupsPerSecond, 1.second)

  val buffer = Flow[MavenDependency].buffer(5, akka.stream.OverflowStrategy.backpressure)

  val flow = parseFile.via(instantiateClasses)
    .via(groupByLibraryName)
    .via(throttle)
    .via(buffer)
    .map(_.library)

  val sink = Sink.foreach(println)

  source.via(flow).to(sink).run()
}

def parseLine(line: String): MavenDependency = {
  val parts = line.split(",")
  val libraryParts = parts(0).split(":")
  val dependencyParts = parts(1).split(":")
  val library = Library(libraryParts(0), libraryParts(1), libraryParts(2))
  val dependency = Dependency(dependencyParts(0), dependencyParts(1), dependencyParts(2))
  val dependencyType = parts(2) match {
    case "Compile" => DependencyType.COMPILE
    case "Runtime" => DependencyType.RUNTIME
  }
  MavenDependency(library, dependency, dependencyType)
}