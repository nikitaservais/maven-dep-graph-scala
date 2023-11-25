import akka.actor.ActorSystem
import akka.stream.{FlowShape, Graph}
import akka.stream.scaladsl.*
import akka.util.ByteString

import java.nio.file.Paths
import concurrent.duration.DurationInt

val MAX_SUBSTREAMS = 185
val GroupsPerSecond = 10 // Throttle rate

case class MavenDependency(library: Library, dependency: Dependency)

case class Library(GroupId: String, ArtifactId: String, Version: String)

case class Dependency(GroupId: String, ArtifactId: String, Version: String, DependencyType: DependencyType);

case class MavenLibrary(library: Library, dependencies: List[Dependency], compileCount: Int, runtimeCount: Int) {
  override def toString: String = {
    val numberOfCharacters = library.GroupId.length + library.ArtifactId.length + library.Version.length
    val numberOfSpaces = 65 - numberOfCharacters
    s"Name: ${library.GroupId} ${library.ArtifactId} ${library.Version} ${" " * numberOfSpaces}--> Compile: ${compileCount}${" " * (3 - compileCount.toString.length)} Runtime: ${runtimeCount}"
  }
}

enum DependencyType {
  case COMPILE, RUNTIME
}

def parseLine(line: String): MavenDependency = {
  val parts = line.split(",")
  val libraryParts = parts(0).split(":")
  val dependencyParts = parts(1).split(":")
  val library = Library(libraryParts(0), libraryParts(1), libraryParts(2))
  val dependencyType = parts(2) match {
    case "Compile" => DependencyType.COMPILE
    case "Runtime" => DependencyType.RUNTIME
  }
  val dependency = Dependency(dependencyParts(0), dependencyParts(1), dependencyParts(2), dependencyType)
  MavenDependency(library, dependency)
}

object Main extends App {
  implicit val system: ActorSystem = ActorSystem("QuickStart")

  val source = FileIO.fromPath(Paths.get("maven_dependencies.csv"))

  val parseFile = Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true)
    .map(_.utf8String)
    .drop(1)

  val instantiateClasses = Flow[String].map(parseLine)
  // this will groups by Library, so the output is Library, List(Dependency)
  val groupByLibraryName = Flow[MavenDependency].groupBy(MAX_SUBSTREAMS, _.library)
    .fold(MavenLibrary(null, List(), 0, 0))((acc, value) => {
      if (acc.library == null) {
        MavenLibrary(value.library, List(value.dependency), 0, 0)
      } else {
        MavenLibrary(value.library, acc.dependencies :+ value.dependency, 0, 0)
      }
    })
    .mergeSubstreams

  val throttle = Flow[MavenLibrary].throttle(GroupsPerSecond, 1.second)

  val buffer = Flow[MavenLibrary].buffer(5, akka.stream.OverflowStrategy.backpressure)

  val countDependenciesGraph: Graph[FlowShape[MavenLibrary, MavenLibrary], akka.NotUsed] = GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    // Define the stages
    val broadcast = builder.add(Broadcast[MavenLibrary](2))
    val countCompile = Flow[MavenLibrary].map(lib => lib.copy(compileCount = lib.dependencies.count(_.DependencyType == DependencyType.COMPILE)))
    val countRuntime = Flow[MavenLibrary].map(lib => lib.copy(runtimeCount = lib.dependencies.count(_.DependencyType == DependencyType.RUNTIME)))
    val zip = builder.add(ZipWith[MavenLibrary, MavenLibrary, MavenLibrary]((compileLib, runtimeLib) =>
      MavenLibrary(compileLib.library, compileLib.dependencies, compileLib.compileCount, runtimeLib.runtimeCount)))

    // Connect the stages
    broadcast.out(0) ~> countCompile ~> zip.in0
    broadcast.out(1) ~> countRuntime ~> zip.in1

    FlowShape(broadcast.in, zip.out)
  }

  val balanceAndCountDependenciesGraph: Graph[FlowShape[MavenLibrary, MavenLibrary], akka.NotUsed] = GraphDSL.create() { implicit builder =>
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

  val countDependenciesFlow = Flow.fromGraph(balanceAndCountDependenciesGraph)

  val flow = parseFile.via(instantiateClasses)
    .via(groupByLibraryName)
    .via(throttle)
    .via(buffer)
    .via(countDependenciesFlow) // Using the custom Flow
    .takeWithin(1.second)

  val sink = Sink.foreach(println)

  source.via(flow).to(sink).run()
}
