package scalogno

import scala.collection._

class SmtSolver {
  var lines: List[List[String]] = Nil
  var smt: Exe = _
  def init() = {
    smt = new Exe("cvc4 -m --interactive --lang smt")
    smt.skipHeader()
    smt.write("(set-logic ALL_SUPPORTED)")
  }
  def checkSat(): Boolean = {
    smt.write("(check-sat)")
    smt.readAtom() match {
      case "sat" => true
      case "unsat" => false
      //case debug => println("smt gives "+debug); true
    }
  }
  var scope = 0
  def push(): Unit = {
    scope += 1
    lines = Nil::lines
    smt.write("(push)")
  }
  def pop(): Unit = {
    scopes = scopes.collect{case (k,v) if v < scope => (k,v)}.toMap
    if (scope == 0) { return }
    scope -= 1
    lines = lines.tail
    smt.write("(pop)")
  }
  def reset(): Unit = {
    scopes = Map.empty
    lines = Nil
    scope = 0
    smt.write("(reset)")
    smt.write("(set-logic ALL_SUPPORTED)")
  }
  type State = (List[List[String]], Map[Int,Int],Int)
  def state: State = (lines,scopes,scope)
  def restore(s: State): Unit = {
    reset()
    lines = s._1
    scopes = s._2
    scope = s._3
    for (block <- lines.reverse) {
      smt.write("(push)")
      for (line <- block.reverse) {
        smt.write(line)
      }
    }
  }
  var scopes: Map[Int,Int] = Map.empty
  def decl(id: Int): Unit = {
    scopes.get(id) match {
      case None =>
        add(s"(declare-const x$id Int)")
        scopes += (id -> scope)
      case Some(s) =>
        if (s >= scope) {
          add(s"(declare-const x$id Int)")
          scopes += (id -> scope)
        }
    }
  }
  def add(c: String): Unit = {
    print(scope.toString+" ")
    lines = (c::lines.head)::lines.tail
    smt.write(c)
  }
  def extractModel(f: (Int,Int) => Unit): Unit = {
    checkSat()
    smt.write("(get-model)")
    val s = smt.readSExp()
    val p = raw"\(define-fun x([0-9]+) \(\) Int ([0-9]+)\)".r
    for (m <- p.findAllMatchIn(s)) {
      val id = m.group(1).toInt
      val v = m.group(2).toInt
      println(s"$id has $v")
      f(id, v)
    }
  }
}

trait Smt extends Base with InjectBase {
  abstract class Z[+T]
  case class A[+T](x: Exp[T]) extends Z[T] {
    override def toString = {
      val cs = cstore.collect{ case(IsTerm(id, k, _)) if x.id == id => k}.toIterator
      if (cs.hasNext) cs.next else { addVar(x.id); "x"+x.id }
    }
  }
  case class P[+T](s: String, args: List[Z[Any]]) extends Z[T] {
    override def toString = {
      val a = args.mkString(" ")
      s"($s $a)"
    }
  }

  implicit object InjectInt extends Inject[Int] {
    def toTerm(i: Int): Exp[Int] = term(i.toString,Nil)
  }
  implicit def int2ZInt(e: Int): Z[Int] = toZInt(InjectInt.toTerm(e))
  implicit def toZInt(e: Exp[Int]): Z[Int] = A(e)
  implicit def toZIntOps(e: Exp[Int]) = ZIntOps(A(e))
  implicit class ZIntOps(a: Z[Int]) {
    def ==?(b: Z[Int]): Rel = zAssert(P("=", List(a, b)))
    def !=?(b: Z[Int]): Rel = zAssert(P("not", List(P("=", List(a, b)))))
    def >(b: Z[Int]): Rel = zAssert(P(">", List(a, b)))
    def >=(b: Z[Int]): Rel = zAssert(P(">=", List(a, b)))
    def -(b: Z[Int]): Z[Int] = P("-", List(a, b))
    def *(b: Z[Int]): Z[Int] = P("*", List(a, b))
    def +(b: Z[Int]): Z[Int] = P("+", List(a, b))
  }

  val seenvars0: immutable.Set[Int] = immutable.Set.empty
  var seenvars = seenvars0
  def addVar(id: Int) = { seenvars += id }
  def zAssert(p: P[Boolean]): Rel = {
    seenvars = seenvars0
    val c = P("assert", List(p)).toString
    seenvars.foreach{solver.decl}
    solver.add(c)
    if (!solver.checkSat()) throw Backtrack
    Yes
  }
}

class Exe(command: String) {

  import scala.sys.process._
  import scala.io._
  import java.io._
  import scala.concurrent._

  val inputStream = new SyncVar[OutputStream]
  val outputStream = new SyncVar[BufferedReader]

  val process = Process(command).run(
    new ProcessIO(
      stdin => inputStream.put(stdin),
      stdout => outputStream.put(new BufferedReader(new InputStreamReader(stdout))),
      stderr => Source.fromInputStream(stderr).getLines.foreach(println)));

  def skipHeader() = synchronized {
    (0 until 30/*brittle magic*/).foreach{_ => readLine()}
  }

  def readLine(): String = synchronized {
    outputStream.get.readLine()
  }

  def readAtom(): String = synchronized {
    readLine().replaceAll("CVC4> ", "")/*brittle magic*/
  }

  def readSExp(): String = synchronized {
    var sb = new StringBuffer()
    var pl = 0
    var pr = 0
    def processLine() = {
      val line = readLine()
      pl += line.count(_ == '(')
      pr += line.count(_ == ')')
      sb.append(line)
      sb.append(" ")
    }
    var first = true;
    while (first || (pl != pr)) {
      processLine()
      first = false
    }
    sb.toString
  }

  def write(s: String): Unit = synchronized {
    println("smt: "+s)
    inputStream.get.write((s + "\n\n").getBytes)
    inputStream.get.flush()
  }

  def close(): Unit = {
    inputStream.get.close
    outputStream.get.close
  }
}

object Play extends Smt with Engine
