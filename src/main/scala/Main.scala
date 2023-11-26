import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{FlowShape, Graph, IOResult}
import akka.stream.scaladsl.*
import akka.util.ByteString

import java.nio.file.Paths
import concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}


case class MavenDependency(library: Library, dependency: Dependency)

case class Library(GroupId: String, ArtifactId: String, Version: String)

case class Dependency(GroupId: String, ArtifactId: String, Version: String, DependencyType: DependencyType);

case class MavenLibrary(library: Library, dependencies: List[Dependency], compileCount: Int, runtimeCount: Int) {
  override def toString: String = {
    val numberOfSpaces = 65 - (library.GroupId.length + library.ArtifactId.length + library.Version.length)
    s"Name: ${library.GroupId} ${library.ArtifactId} ${library.Version} ${" " * numberOfSpaces}--> Compile: $compileCount${" " * (3 - compileCount.toString.length)} Runtime: $runtimeCount"
  }
}

enum DependencyType {
  case COMPILE, RUNTIME
}

object LineParser {
  def parseLine(line: String): MavenDependency = {
    val parts = line.split(",")
    val library = parseLibrary(parts(0))
    val dependency = parseDependency(parts(1), parts(2))
    MavenDependency(library, dependency)
  }

  private def parseLibrary(libraryStr: String): Library = {
    val parts = libraryStr.split(":")
    Library(parts(0), parts(1), parts(2))
  }

  private def parseDependency(dependencyStr: String, dependencyTypeStr: String): Dependency = {
    val parts = dependencyStr.split(":")
    val dependencyType = parseDependencyType(dependencyTypeStr)
    Dependency(parts(0), parts(1), parts(2), dependencyType)
  }

  private def parseDependencyType(dependencyTypeStr: String): DependencyType = {
    dependencyTypeStr match {
      case "Compile" => DependencyType.COMPILE
      case "Runtime" => DependencyType.RUNTIME
    }
  }
}

object DependencyCounter {
  private def countDependencies(lib: MavenLibrary, dependencyType: DependencyType): MavenLibrary = {
    lib.copy(
      compileCount = if (dependencyType == DependencyType.COMPILE) lib.dependencies.count(_.DependencyType == dependencyType) else lib.compileCount,
      runtimeCount = if (dependencyType == DependencyType.RUNTIME) lib.dependencies.count(_.DependencyType == dependencyType) else lib.runtimeCount
    )
  }

  private def countDependenciesGraph: Graph[FlowShape[MavenLibrary, MavenLibrary], akka.NotUsed] = GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    val broadcast = builder.add(Broadcast[MavenLibrary](2))
    val zip = builder.add(ZipWith[MavenLibrary, MavenLibrary, MavenLibrary]((compileLib, runtimeLib) =>
      MavenLibrary(compileLib.library, compileLib.dependencies, compileLib.compileCount, runtimeLib.runtimeCount)))

    broadcast.out(0) ~> Flow[MavenLibrary].map(countDependencies(_, DependencyType.COMPILE)) ~> zip.in0
    broadcast.out(1) ~> Flow[MavenLibrary].map(countDependencies(_, DependencyType.RUNTIME)) ~> zip.in1

    FlowShape(broadcast.in, zip.out)
  }

  def balanceAndCountDependenciesGraph: Graph[FlowShape[MavenLibrary, MavenLibrary], akka.NotUsed] = GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    // Define the stages
    val balance = builder.add(Balance[MavenLibrary](2))
    val merge = builder.add(Merge[MavenLibrary](2))

    // Instantiate countDependenciesGraph for each pipeline
    val countDependenciesFlow1 = builder.add(countDependenciesGraph)
    val countDependenciesFlow2 = builder.add(countDependenciesGraph)

    // Connect the stages
    balance.out(0) ~> countDependenciesFlow1 ~> merge.in(0)
    balance.out(1) ~> countDependenciesFlow2 ~> merge.in(1)

    FlowShape(balance.in, merge.out)
  }

}


def getFile(file: String): Source[ByteString, Future[IOResult]] =
  FileIO.fromPath(Paths.get(file))

def parseFile: Flow[ByteString, String, NotUsed] =
  Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true)
    .map(_.utf8String)
    .drop(1)

def instantiateClasses: Flow[String, MavenDependency, NotUsed] =
  Flow[String].map(LineParser.parseLine)

def throttleGroups(groupsPerSecond: Int): Flow[MavenLibrary, MavenLibrary, NotUsed] =
  Flow[MavenLibrary].throttle(groupsPerSecond, 1.second)

def bufferGroups(bufferSize: Int): Flow[MavenLibrary, MavenLibrary, NotUsed] =
  Flow[MavenLibrary].buffer(bufferSize, akka.stream.OverflowStrategy.backpressure)

def countDependenciesFlow: Flow[MavenLibrary, MavenLibrary, NotUsed] =
  Flow.fromGraph(DependencyCounter.balanceAndCountDependenciesGraph)

def groupByLibraryName(maxSubstreams: Int): Flow[MavenDependency, MavenLibrary, NotUsed] =
  Flow[MavenDependency].groupBy(maxSubstreams, _.library)
    .fold(MavenLibrary(null, List(), 0, 0))((acc, value) => {
      if (acc.library == null) {
        MavenLibrary(value.library, List(value.dependency), 0, 0)
      } else {
        MavenLibrary(value.library, acc.dependencies :+ value.dependency, 0, 0)
      }
    })
    .mergeSubstreams

object Main extends App {
  implicit val system: ActorSystem = ActorSystem("MavenDependencyParser")

  private val maxSubstreams = 185
  private val groupsPerSecond = 10
  private val bufferSize = 5
  private val file = "maven_dependencies.csv"

  private val mavenDependencyParser = getFile(file)
    .via(parseFile)
    .via(instantiateClasses)
    .via(groupByLibraryName(maxSubstreams))
    .via(throttleGroups(groupsPerSecond))
    .via(bufferGroups(bufferSize))
    .via(countDependenciesFlow)
    .runWith(Sink.foreach(println))

  mavenDependencyParser.onComplete {
    case Success(_) =>
      println("Maven dependency parser processing completed successfully.")
      system.terminate()
    case Failure(e) =>
      println(s"Maven dependency parser  processing failed with: ${e.getMessage}")
      system.terminate()
  }
}
