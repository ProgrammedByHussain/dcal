package distcompiler

import cats.syntax.all.given
import util.{++, toShortString}

enum Manip[+T]:
  private inline given DebugInfo = DebugInfo.poison

  case Backtrack(debugInfo: DebugInfo)
  case Pure(value: T)
  case Ap[T, U](ff: Manip[T => U], fa: Manip[T]) extends Manip[U]
  case FlatMap[T, U](manip: Manip[T], fn: T => Manip[U]) extends Manip[U]

  case Anchor[T]() extends Manip[T]
  case Restrict[T, U](
      manip: Manip[T],
      restriction: Manip.Restriction[T, U],
      debugInfo: DebugInfo,
      continuation: Manip[U]
  ) extends Manip[U]
  case Table[T, U, V](
      ref: Manip.Ref[T],
      restriction: Manip.Restriction[T, U],
      debugInfo: DebugInfo,
      map: Map[U, Manip[V]]
  ) extends Manip[V]

  case Effect(fn: () => T)
  case Finally(manip: Manip[T], fn: () => Unit)

  case KeepLeft(left: Manip[T], right: Manip[?])
  case KeepRight(left: Manip[?], right: Manip[T])

  case Commit(manip: Manip[T], debugInfo: DebugInfo)
  case Negated(manip: Manip[?], debugInfo: DebugInfo) extends Manip[Unit]

  case RefInit[T, U](ref: Manip.Ref[T], initFn: () => T, manip: Manip[U])
      extends Manip[U]
  case RefGet[T](ref: Manip.Ref[T], debugInfo: DebugInfo) extends Manip[T]
  case RefUpdated[T, U](
      ref: Manip.Ref[T],
      fn: T => T,
      manip: Manip[U],
      debugInfo: DebugInfo
  ) extends Manip[U]
  case GetRefMap extends Manip[Manip.RefMap]
  case GetTracer extends Manip[Manip.Tracer]

  case Disjunction(first: Manip[T], second: Manip[T])
  case Deferred(fn: () => Manip[T])

  def isBacktrack: Boolean =
    this match
      case Backtrack(_) => true
      case _            => false

  def perform(using DebugInfo)(): T =
    import scala.util.control.TailCalls.*

    import Manip.AnchorVal
    type Continue[-T, +U] = T => TailRec[U]
    type Backtrack[+U] = DebugInfo => TailRec[U]
    type RefMap = Manip.RefMap

    def tracer(using refMap: RefMap): Manip.Tracer =
      refMap.get(Manip.tracerRef) match
        case None         => Manip.NopTracer
        case Some(tracer) => tracer

    def handleOpt(using refMap: RefMap): Option[Manip.Handle] =
      refMap.get(Manip.Handle.ref)

    def emptyBacktrack(
        ctxInfo: DebugInfo,
        refMap: RefMap
    ): Backtrack[Nothing] =
      posInfo =>
        tracer(using refMap).onFatal(ctxInfo, posInfo)(using refMap)

        throw RuntimeException(
          s"unrecovered backtrack at $posInfo, caught at $ctxInfo, at ${refMap.treeDescr}"
        )

    def impl[T, U](self: Manip[T])(using
        continue: Continue[T, U],
        backtrack: Backtrack[U],
        refMap: RefMap,
        anchorVal: AnchorVal
    ): TailRec[U] =
      self match
        case Backtrack(debugInfo) =>
          tracer.onBacktrack(debugInfo)
          tailcall(backtrack(debugInfo))
        case Pure(value) => tailcall(continue(value))
        case ap: Ap[t, u] =>
          given Continue[t => u, U] = ff =>
            given Continue[t, U] = fa => tailcall(continue(ff(fa)))
            tailcall(impl(ap.fa))
          impl(ap.ff)
        case flatMap: FlatMap[t, u] =>
          given Continue[t, U] = value =>
            given Continue[u, U] = continue
            tailcall(impl(flatMap.fn(value)))
          impl(flatMap.manip)
        case Anchor() => tailcall(continue(anchorVal.value.asInstanceOf[T]))
        case restrict: Restrict[t, u] =>
          given Continue[t, U] = value =>
            restrict.restriction.unapply(value) match
              case None =>
                tracer.onBacktrack(restrict.debugInfo)
                tailcall(backtrack(restrict.debugInfo))
              case Some(value) =>
                given AnchorVal = AnchorVal(value)
                given Continue[T, U] = continue
                tailcall(impl(restrict.continuation))
          impl(restrict.manip)
        case table: Table[t, u, v] =>
          refMap.get(table.ref) match
            case None => tailcall(backtrack(table.debugInfo))
            case Some(value) =>
              table.restriction.unapply(value) match
                case None => tailcall(backtrack(table.debugInfo))
                case Some(value) =>
                  table.map.get(value) match
                    case None => tailcall(backtrack(table.debugInfo))
                    case Some(followUp) =>
                      given AnchorVal = AnchorVal(value)
                      impl(followUp)
        case Effect(fn) =>
          val value = fn()
          tailcall(continue(value))
        case Finally(manip, fn) =>
          given Backtrack[U] = info =>
            fn()
            tailcall(backtrack(info))
          given Continue[T, U] = value =>
            fn()
            tailcall(continue(value))

          impl(manip)
        case KeepLeft(left, right: Manip[t]) =>
          given Continue[T, U] = value =>
            given Continue[t, U] = _ => tailcall(continue(value))
            tailcall(impl(right))
          impl(left)
        case KeepRight(left: Manip[t], right) =>
          given Continue[t, U] = _ =>
            given Continue[T, U] = continue
            tailcall(impl[T, U](right))
          impl(left)
        case Commit(manip, debugInfo) =>
          tracer.onCommit(debugInfo)
          given Backtrack[U] = emptyBacktrack(debugInfo, refMap)
          impl(manip)
        case Negated(manip: Manip[t], debugInfo) =>
          given Backtrack[U] = _ => tailcall(continue(()))
          given Continue[t, U] = _ =>
            tracer.onBacktrack(debugInfo)
            tailcall(backtrack(debugInfo))
          impl(manip)
        case RefInit(ref, initFn, manip) =>
          given RefMap = refMap.updated(ref, initFn())
          impl(manip)
        case RefGet(ref, debugInfo) =>
          refMap.get(ref) match
            case None =>
              tracer.onBacktrack(debugInfo)
              tailcall(backtrack(debugInfo))
            case Some(value) => tailcall(continue(value))
        case refUpdated: RefUpdated[t, T @unchecked] =>
          refMap.get(refUpdated.ref) match
            case None =>
              tracer.onBacktrack(refUpdated.debugInfo)
              tailcall(backtrack(refUpdated.debugInfo))
            case Some(value) =>
              given RefMap = refMap.updated(
                refUpdated.ref,
                refUpdated.fn(value)
              )
              impl(refUpdated.manip)
        case GetRefMap =>
          tailcall(continue(refMap))
        case GetTracer =>
          tailcall(continue(tracer))
        case Disjunction(first, second) =>
          given Backtrack[U] = debugInfo1 =>
            given Backtrack[U] = debugInfo2 =>
              tailcall(backtrack(debugInfo1 ++ debugInfo2))
            tailcall(impl(second))
          impl(first)
        case Deferred(fn) => impl(fn())

    given Continue[T, T] = done
    given RefMap = Manip.RefMap.empty
    given Backtrack[T] = emptyBacktrack(summon[DebugInfo], summon[RefMap])
    given AnchorVal = AnchorVal(())
    impl[T, T](this).result
  end perform
end Manip

object Manip:
  private inline given DebugInfo = DebugInfo.poison

  abstract class Ref[T]:
    final def init[U](init: => T)(manip: Manip[U]): Manip[U] =
      Manip.RefInit(this, () => init, manip)
    final def get(using DebugInfo): Manip[T] =
      Manip.RefGet(this, summon[DebugInfo])
    final def updated[U](using DebugInfo)(fn: T => T)(
        manip: Manip[U]
    ): Manip[U] = Manip.RefUpdated(this, fn, manip, summon[DebugInfo])
    final def doEffect[U](using DebugInfo)(fn: T => U): Manip[U] =
      import ops.{lookahead, effect}
      get.lookahead.flatMap: v =>
        effect(fn(v))

    override def equals(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]
    override def hashCode(): Int = System.identityHashCode(this)

  final class RefMap(private val map: Map[Manip.Ref[?], Any]) extends AnyVal:
    def get[T](ref: Ref[T]): Option[T] =
      map.get(ref).asInstanceOf[Option[T]]

    def updated[T](ref: Ref[T], value: T): RefMap =
      RefMap(map.updated(ref, value))

    def treeDescr: String =
      get(Handle.ref) match
        case None => "<no tree>"
        case Some(handle) =>
          handle match
            case Handle.AtTop(top) => s"top $top"
            case Handle.AtChild(parent, idx, child) =>
              if parent.children.lift(idx).exists(_ eq child)
              then s"child $idx of $parent"
              else s"!!mismatch child $idx of $parent\n!!and $child"
            case Handle.Sentinel(parent, idx) =>
              s"end [idx=$idx] of $parent"

    def treeDescrShort: String =
      treeDescr.toShortString()

  object RefMap:
    def empty: RefMap = RefMap(Map.empty)

  type Rules = Manip[RulesResult]

  enum RulesResult:
    case Progress, NoProgress

  val unit: Manip[Unit] = ().pure
  export ops.defer
  export applicative.pure

  private final class AnchorVal(val value: Any) extends AnyVal

  abstract class Restriction[-T, +U] extends PartialFunction[T, U]:
    def combineRestrictions[UU >: U, V](
        restriction: Restriction[UU, V]
    ): Restriction[T, V] =
      Restriction.Combination(this, restriction)

    protected val impl: PartialFunction[T, U]

    export impl.{apply, isDefinedAt}
    override def applyOrElse[A1 <: T, B1 >: U](x: A1, default: A1 => B1): B1 =
      impl.applyOrElse(x, default)

  object Restriction:
    def apply[T, U](fn: PartialFunction[T, U]): Restriction[T, U] =
      fn match
        case fn: Restriction[T, U] => fn
        case _                     => Wrapper(fn)

    final class Identity[T] extends Restriction[T, T]:
      protected val impl: PartialFunction[T, T] = identity(_)

      override def equals(that: Any): Boolean =
        that.isInstanceOf[Identity[?]]
      override def hashCode(): Int =
        getClass().hashCode()

    final case class Combination[T, U, V](
        lhs: Restriction[T, U],
        rhs: Restriction[U, V]
    ) extends Restriction[T, V]:
      protected val impl: PartialFunction[T, V] = rhs.impl.compose(lhs.impl)

    final class Wrapper[T, U](fn: PartialFunction[T, U])
        extends Restriction[T, U]:
      protected val impl = fn
      override def equals(that: Any): Boolean =
        that match
          case that: Wrapper[?, ?] => impl eq that.impl
          case _                   => false
      override def hashCode(): Int = impl.hashCode()

  private def pushRight[T, U](lhs: Manip[T])(
      fn: Manip[T] => Manip[U]
  ): Manip[U] =
    lhs match
      case table @ Manip.Table(_, _, _, map) =>
        table.copy(map = map.view.mapValues(fn).toMap)
      case _ => fn(lhs)

  private def pushRight2[T1, T2, U](lhs1: Manip[T1], lhs2: Manip[T2])(
      fn: (Manip[T1], Manip[T2]) => Manip[U]
  ): Manip[U] =
    pushRight(lhs1): lhs1 =>
      pushRight(lhs2): lhs2 =>
        fn(lhs1, lhs2)

  given applicative: cats.Applicative[Manip] with
    override def unit: Manip[Unit] = Manip.unit
    def ap[A, B](ff: Manip[A => B])(fa: Manip[A]): Manip[B] =
      pushRight2(ff, fa): (ff, fa) =>
        Manip.Ap(ff, fa)
    def pure[A](x: A): Manip[A] = Manip.Pure(x)
    override def as[A, B](fa: Manip[A], b: B): Manip[B] =
      productR(fa)(pure(b))
    override def productL[A, B](fa: Manip[A])(fb: Manip[B]): Manip[A] =
      pushRight2(fa, fb): (fa, fb) =>
        Manip.KeepLeft(fa, fb)
    override def productR[A, B](fa: Manip[A])(fb: Manip[B]): Manip[B] =
      pushRight2(fa, fb): (fa, fb) =>
        Manip.KeepRight(fa, fb)

  given semigroupK: cats.SemigroupK[Manip] with
    def combineK[A](x: Manip[A], y: Manip[A]): Manip[A] =
      (x, y) match
        case (Manip.Backtrack(_), right)             => right
        case (left, Manip.Backtrack(_))              => left
        case (left @ Manip.Anchor(), Manip.Anchor()) => left
        case (
              Manip.Restrict(
                Manip.RefGet(ref1, debugInfo1),
                restrict1,
                debugInfo11,
                continuation1
              ),
              Manip.Restrict(
                Manip.RefGet(ref2, debugInfo2),
                restrict2,
                debugInfo22,
                continuation2
              )
            ) if ref1 == ref2 && restrict1 == restrict2 =>
          Manip.Restrict(
            Manip.RefGet(ref1, debugInfo1 ++ debugInfo2),
            restrict1,
            debugInfo11 ++ debugInfo22,
            combineK(continuation1, continuation2)
          )
        // a =:= a2 =:= A
        case (table1: Manip.Table[t, u, a], table2: Manip.Table[t2, u2, a2])
            if table1.ref == table2.ref && table1.restriction == table2.restriction =>
          val table2Adapt = table2.asInstanceOf[Manip.Table[t, u, a]]
          val combinedMap =
            (table1.map.keysIterator ++ table2Adapt.map.keysIterator)
              .map: key =>
                (table1.map.get(key), table2Adapt.map.get(key)) match
                  case (Some(m1), Some(m2)) => key -> combineK(m1, m2)
                  case (Some(m1), _)        => key -> m1
                  case (_, Some(m2))        => key -> m2
                  case _                    => ??? // unreachable
              .toMap
          Manip.Table(
            table1.ref,
            table1.restriction,
            table1.debugInfo ++ table2Adapt.debugInfo,
            combinedMap
          )
        case _ =>
          Manip.Disjunction(x, y)

  // given monoidK(using DebugInfo): cats.MonoidK[Manip] with
  //   export semigroupK.combineK
  //   def empty[A]: Manip[A] = Manip.Backtrack(summon[DebugInfo])

  class Lookahead[T](val manip: Manip[T]) extends AnyVal:
    def flatMap[U](fn: T => Manip[U]): Manip[U] =
      pushRight(manip): manip =>
        Manip.FlatMap(manip, fn)

  object Lookahead:
    given applicative: cats.Applicative[Lookahead] with
      def ap[A, B](ff: Lookahead[A => B])(fa: Lookahead[A]): Lookahead[B] =
        Lookahead(ff.manip.ap(fa.manip))
      def pure[A](x: A): Lookahead[A] =
        Lookahead(Manip.pure(x))
    given semigroupK: cats.SemigroupK[Lookahead] with
      def combineK[A](x: Lookahead[A], y: Lookahead[A]): Lookahead[A] =
        Lookahead(x.manip.combineK(y.manip))
    // given monoidK(using DebugInfo): cats.MonoidK[Lookahead] with
    //   export semigroupK.combineK
    //   def empty[A]: Lookahead[A] =
    //     Lookahead(backtrack)

  object tracerRef extends Ref[Tracer]

  trait Tracer extends java.io.Closeable:
    def beforePass(debugInfo: DebugInfo)(using RefMap): Unit
    def afterPass(debugInfo: DebugInfo)(using RefMap): Unit
    def onCommit(debugInfo: DebugInfo)(using RefMap): Unit
    def onBacktrack(debugInfo: DebugInfo)(using RefMap): Unit
    def onFatal(debugInfo: DebugInfo, from: DebugInfo)(using RefMap): Unit
    def onRewriteMatch(
        debugInfo: DebugInfo,
        parent: Node.Parent,
        idx: Int,
        matchedCount: Int
    )(using RefMap): Unit
    def onRewriteComplete(
        debugInfo: DebugInfo,
        parent: Node.Parent,
        idx: Int,
        resultCount: Int
    )(using RefMap): Unit

  abstract class AbstractNopTracer extends Tracer:
    def beforePass(debugInfo: DebugInfo)(using RefMap): Unit = ()
    def afterPass(debugInfo: DebugInfo)(using RefMap): Unit = ()
    def onCommit(debugInfo: DebugInfo)(using RefMap): Unit = ()
    def onBacktrack(debugInfo: DebugInfo)(using RefMap): Unit = ()
    def onFatal(debugInfo: DebugInfo, from: DebugInfo)(using RefMap): Unit = ()
    def onRewriteMatch(
        debugInfo: DebugInfo,
        parent: Node.Parent,
        idx: Int,
        matchedCount: Int
    )(using RefMap): Unit = ()
    def onRewriteComplete(
        debugInfo: DebugInfo,
        parent: Node.Parent,
        idx: Int,
        resultCount: Int
    )(using RefMap): Unit = ()
    def close(): Unit = ()

  object NopTracer extends AbstractNopTracer

  final class LogTracer(out: java.io.OutputStream, limit: Int = -1)
      extends Tracer:
    def this(path: os.Path, limit: Int) =
      this(os.write.over.outputStream(path, createFolders = true), limit)
    def this(path: os.Path) =
      this(path, limit = -1)

    export out.close

    private var lineCount: Int = 0

    private def logln(str: String): Unit =
      out.write(str.getBytes())
      out.write('\n')
      out.flush()

      lineCount += 1

      if limit != -1 && lineCount >= limit
      then throw RuntimeException(s"logged $lineCount lines")

    private def treeDesc(using RefMap): String =
      summon[RefMap].treeDescrShort

    def beforePass(debugInfo: DebugInfo)(using RefMap): Unit =
      logln(s"pass init $debugInfo at $treeDesc")

    def afterPass(debugInfo: DebugInfo)(using RefMap): Unit =
      logln(s"pass end $debugInfo at $treeDesc")

    def onCommit(debugInfo: DebugInfo)(using RefMap): Unit =
      logln(s"commit $debugInfo at $treeDesc")

    def onBacktrack(debugInfo: DebugInfo)(using RefMap): Unit =
      logln(s"backtrack $debugInfo at $treeDesc")

    def onFatal(debugInfo: DebugInfo, from: DebugInfo)(using RefMap): Unit =
      logln(s"fatal $debugInfo from $from at $treeDesc")

    def onRewriteMatch(
        debugInfo: DebugInfo,
        parent: Node.Parent,
        idx: Int,
        matchedCount: Int
    )(using RefMap): Unit =
      logln(
        s"rw match $debugInfo, from $idx, $matchedCount nodes in parent $parent"
      )

    def onRewriteComplete(
        debugInfo: DebugInfo,
        parent: Node.Parent,
        idx: Int,
        resultCount: Int
    )(using RefMap): Unit =
      logln(
        s"rw done $debugInfo, from $idx, $resultCount nodes in parent $parent"
      )

  final class RewriteDebugTracer(debugFolder: os.Path)
      extends AbstractNopTracer:
    os.remove.all(debugFolder) // no confusing left-over files!
    os.makeDir.all(debugFolder)
    private var passCounter = 0
    private var rewriteCounter = 0

    private def treeDesc(using RefMap): geny.Writable =
      summon[RefMap].get(Manip.Handle.ref) match
        case None => "<no tree>"
        case Some(handle) =>
          handle.findTop match
            case None => "<??? top not found ???>"
            case Some(top) =>
              top.toPrettyWritable(Wellformed.empty)

    private def pathAt(
        passIdx: Int,
        rewriteIdx: Int,
        isDiff: Boolean = false
    ): os.Path =
      val ext = if isDiff then ".diff" else ".txt"
      debugFolder / f"$passIdx%03d" / f"$rewriteIdx%03d$ext%s"

    private def currPath: os.Path =
      pathAt(passCounter, rewriteCounter)

    private def prevPath: os.Path =
      pathAt(passCounter, rewriteCounter - 1)

    private def diffPath: os.Path =
      pathAt(passCounter, rewriteCounter, isDiff = true)

    private def takeSnapshot(debugInfo: DebugInfo)(using RefMap): Unit =
      os.write(
        target = currPath,
        data = (s"//! $debugInfo\n": geny.Writable) ++ treeDesc ++ "\n",
        createFolders = true
      )

    private def takeDiff()(using RefMap): Unit =
      val result =
        os.proc(
          "diff",
          "--report-identical-files",
          "--ignore-space-change",
          prevPath,
          currPath
        ).call(
          stdout = os.PathRedirect(diffPath),
          check = false
        )
      if result.exitCode == 0 || result.exitCode == 1
      then () // fine (1 is used to mean files are different)
      else throw RuntimeException(result.toString())

    override def beforePass(debugInfo: DebugInfo)(using RefMap): Unit =
      passCounter += 1
      rewriteCounter = 0
      takeSnapshot(debugInfo)

    override def afterPass(debugInfo: DebugInfo)(using RefMap): Unit =
      rewriteCounter += 1
      takeSnapshot(debugInfo)

    override def onRewriteComplete(
        debugInfo: DebugInfo,
        parent: Node.Parent,
        idx: Int,
        resultCount: Int
    )(using RefMap): Unit =
      rewriteCounter += 1
      takeSnapshot(debugInfo)
      takeDiff()

  enum Handle:
    assertCoherence()

    case AtTop(top: Node.Top)
    case AtChild(parent: Node.Parent, idx: Int, child: Node.Child)
    case Sentinel(parent: Node.Parent, idx: Int)

    def assertCoherence(): Unit =
      this match
        case AtTop(_) =>
        case AtChild(parent, idx, child) =>
          if parent.children.isDefinedAt(idx) && (parent.children(idx) eq child)
          then () // ok
          else
            throw NodeError(
              s"mismatch: idx $idx in ${parent.toShortString()} and ptr -> ${child.toShortString()}"
            )
        case Sentinel(parent, idx) =>
          if parent.children.length != idx
          then
            throw NodeError(
              s"mismatch: sentinel at $idx does not point to end ${parent.children.length} of ${parent.toShortString()}"
            )

    def rightSibling: Option[Handle] =
      assertCoherence()
      this match
        case AtTop(_) => None
        case AtChild(parent, idx, _) =>
          Handle.idxIntoParent(parent, idx + 1)
        case Sentinel(_, _) => None

    def findFirstChild: Option[Handle] =
      assertCoherence()
      this match
        case AtTop(top) => Handle.idxIntoParent(top, 0)
        case AtChild(_, _, child) =>
          child match
            case child: Node.Parent => Handle.idxIntoParent(child, 0)
            case _: Node.Embed[?]   => None
        case Sentinel(_, _) => None

    def findLastChild: Option[Handle] =
      assertCoherence()
      def forParent(parent: Node.Parent): Option[Handle] =
        parent.children.indices.lastOption
          .flatMap(Handle.idxIntoParent(parent, _))

      this match
        case AtTop(top) => forParent(top)
        case AtChild(_, _, child) =>
          child match
            case child: Node.Parent => forParent(child)
            case _: Node.Embed[?]   => None
        case Sentinel(_, _) => None

    def findParent: Option[Handle] =
      assertCoherence()
      this match
        case AtTop(_)              => None
        case AtChild(parent, _, _) => Some(Handle.fromNode(parent))
        case Sentinel(parent, _)   => Some(Handle.fromNode(parent))

    def atIdx(idx: Int): Option[Handle] =
      assertCoherence()
      this match
        case AtTop(_)              => None
        case AtChild(parent, _, _) => Handle.idxIntoParent(parent, idx)
        case Sentinel(parent, _)   => Handle.idxIntoParent(parent, idx)

    def keepIdx: Option[Handle] =
      this match
        case AtTop(_)                => Some(this)
        case AtChild(parent, idx, _) => Handle.idxIntoParent(parent, idx)
        case Sentinel(parent, idx)   => Handle.idxIntoParent(parent, idx)

    def keepPtr: Handle =
      this match
        case AtTop(_)             => this
        case AtChild(_, _, child) => Handle.fromNode(child)
        case Sentinel(parent, _) =>
          Handle.idxIntoParent(parent, parent.children.length).get

    def findTop: Option[Node.Top] =
      def forParent(parent: Node.Parent): Option[Node.Top] =
        val p = parent
        inline given DebugInfo = DebugInfo.notPoison
        import dsl.*
        p.inspect:
          theTop
            | ancestor(theTop)
      this match
        case AtTop(top)            => Some(top)
        case AtChild(parent, _, _) => forParent(parent)
        case Sentinel(parent, _)   => forParent(parent)

  object Handle:
    object ref extends Ref[Handle]

    def fromNode(node: Node.All): Handle =
      node match
        case top: Node.Top => Handle.AtTop(top)
        case child: Node.Child =>
          require(child.parent.nonEmpty)
          Handle.AtChild(child.parent.get, child.idxInParent, child)

    private def idxIntoParent(parent: Node.Parent, idx: Int): Option[Handle] =
      if parent.children.isDefinedAt(idx)
      then Some(AtChild(parent, idx, parent.children(idx)))
      else if idx == parent.children.length
      then Some(Sentinel(parent, idx))
      else None

  object ops:
    def defer[T](manip: => Manip[T]): Manip[T] =
      lazy val impl = manip
      Manip.Deferred(() => impl)

    def commit[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      Manip.Commit(manip, summon[DebugInfo])

    def effect[T](fn: => T): Manip[T] =
      Manip.Effect(() => fn)

    def backtrack(using DebugInfo): Manip[Nothing] =
      Manip.Backtrack(summon[DebugInfo])

    def atNode[T](node: Node.All)(manip: Manip[T]): Manip[T] =
      atHandle(Handle.fromNode(node))(manip)

    def atHandle[T](handle: Manip.Handle)(manip: Manip[T]): Manip[T] =
      Manip.Handle.ref.init(handle)(manip)

    def getHandle(using DebugInfo): Manip[Manip.Handle] =
      Manip.Handle.ref.get

    def getTracer: Manip[Manip.Tracer] =
      Manip.GetTracer

    def keepHandleIdx[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      getHandle.lookahead.flatMap: handle =>
        handle.keepIdx match
          case None         => backtrack
          case Some(handle) => Manip.Handle.ref.updated(_ => handle)(manip)

    def keepHandlePtr[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      getHandle.lookahead.flatMap: handle =>
        Manip.Handle.ref.updated(_ => handle.keepPtr)(manip)

    private case object RestrictNode extends Restriction[Handle, Node.All]:
      protected val impl: PartialFunction[Handle, Node.All] = {
        case Handle.AtTop(top)           => top
        case Handle.AtChild(_, _, child) => child
      }

    def getNode(using DebugInfo): Manip[Node.All] =
      getHandle
        // .tapEffect(_.assertCoherence()) // removed because it gets in the way of tabling
        .restrict(RestrictNode)

    def atRightSibling[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      getHandle.lookahead.flatMap: handle =>
        handle.rightSibling match
          case None => backtrack
          case Some(handle) =>
            Manip.Handle.ref.updated(_ => handle)(manip)

    def atIdx[T](using DebugInfo)(idx: Int)(manip: Manip[T]): Manip[T] =
      getHandle.lookahead.flatMap: handle =>
        handle.atIdx(idx) match
          case None         => backtrack
          case Some(handle) => Manip.Handle.ref.updated(_ => handle)(manip)

    def atFirstChild[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      getHandle.lookahead.flatMap: handle =>
        handle.findFirstChild match
          case None         => backtrack
          case Some(handle) => Manip.Handle.ref.updated(_ => handle)(manip)

    def atLastChild[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      getHandle.lookahead.flatMap: handle =>
        handle.findLastChild match
          case None         => backtrack
          case Some(handle) => Manip.Handle.ref.updated(_ => handle)(manip)

    def atFirstSibling[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      atParent(atFirstChild(manip))

    def atLastSibling[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      atParent(atLastChild(manip))

    def atParent[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      getHandle.lookahead.flatMap: handle =>
        handle.findParent match
          case None         => backtrack
          case Some(handle) => Manip.Handle.ref.updated(_ => handle)(manip)

    def addChild[T](using DebugInfo)(child: => Node.Child): Manip[Node.Child] =
      getNode.lookahead.flatMap:
        case thisParent: Node.Parent =>
          effect:
            val tmp = child
            thisParent.children.addOne(tmp)
            tmp
        case _ => backtrack

    final class on[T](val pattern: Pattern[T]):
      def raw: Manip[(Int, T)] =
        pattern.manip

      def value: Manip[T] =
        raw.map(_._2)

      def check: Manip[Unit] =
        raw.as(())

      def rewrite(using DebugInfo)(
          action: on.Ctx ?=> T => Rules
      ): Rules =
        (raw, getHandle, getTracer, Manip.GetRefMap).tupled
          .flatMap:
            case ((maxIdx, value), handle, tracer, refMap) =>
              handle.assertCoherence()
              val (parent, idx) =
                handle match
                  case Handle.AtTop(top) =>
                    throw NodeError(
                      s"tried to rewrite top ${top.toShortString()}"
                    )
                  case Handle.AtChild(parent, idx, _) => (parent, idx)
                  case Handle.Sentinel(parent, idx)   => (parent, idx)
              val matchedCount =
                if maxIdx == -1
                then 0
                else
                  maxIdx + 1 - idx // +1 because if we matched range 2 - 2 then we saw 1 node
              tracer.onRewriteMatch(
                summon[DebugInfo],
                parent,
                idx,
                matchedCount
              )(using refMap)
              action(using on.Ctx(matchedCount))(value)

    object on:
      final case class Ctx(matchedCount: Int) extends AnyVal

    def spliceThen(using DebugInfo)(using onCtx: on.Ctx)(
        nodes: Iterable[Node.Child]
    )(
        manip: on.Ctx ?=> Rules
    ): Rules =
      // Preparation for the rewrite may have reparented the node we were "on"
      // When you immediately call splice, this pretty much always means "stay at that index and put something there",
      // so that's what we do.
      // Much easier than forcing the end user to understand such a confusing edge case.
      keepHandleIdx:
        (getHandle, getTracer, Manip.GetRefMap).tupled
          .tapEffect: (handle, tracer, refMap) =>
            handle.assertCoherence()
            val (parent, idx) =
              handle match
                case Handle.AtTop(top) =>
                  throw NodeError(s"tried to splice top $top")
                case Handle.AtChild(parent, idx, _) => (parent, idx)
                case Handle.Sentinel(parent, idx)   => (parent, idx)
            parent.children.patchInPlace(idx, nodes, onCtx.matchedCount)
            tracer.onRewriteComplete(
              summon[DebugInfo],
              parent,
              idx,
              nodes.size
            )(using
              refMap
            )
        *> keepHandleIdx(
          pass.resultRef.updated(_ => RulesResult.Progress)(
            manip(using on.Ctx(nodes.size))
          )
        )

    def spliceThen(using DebugInfo, on.Ctx)(nodes: Node.Child*)(
        manip: on.Ctx ?=> Rules
    ): Rules =
      spliceThen(nodes)(manip)

    def spliceThen(using DebugInfo, on.Ctx)(nodes: IterableOnce[Node.Child])(
        manip: on.Ctx ?=> Rules
    ): Rules =
      spliceThen(nodes.iterator.toArray)(manip)

    def splice(using DebugInfo)(using onCtx: on.Ctx, passCtx: pass.Ctx)(
        nodes: Iterable[Node.Child]
    ): Rules =
      spliceThen(nodes)(skipMatch)

    def splice(using DebugInfo, on.Ctx, pass.Ctx)(nodes: Node.Child*): Rules =
      splice(nodes)

    def splice(using DebugInfo, on.Ctx, pass.Ctx)(
        nodes: IterableOnce[Node.Child]
    ): Rules =
      splice(nodes.iterator.toArray)

    def continuePass(using passCtx: pass.Ctx): Rules =
      passCtx.loop

    def continuePassAtNextNode(using pass.Ctx): Rules =
      atNextNode(continuePass)

    def atNextNode(using passCtx: pass.Ctx)(manip: Rules): Rules =
      passCtx.strategy.atNext(manip)

    def skipMatch(using
        DebugInfo
    )(using onCtx: on.Ctx, passCtx: pass.Ctx): Rules =
      getHandle.lookahead.flatMap: handle =>
        handle.assertCoherence()
        val idx =
          handle match
            case Handle.AtTop(top) =>
              throw NodeError(
                s"tried to skip match at top ${top.toShortString()}"
              )
            case Handle.AtChild(_, idx, _) => idx
            case Handle.Sentinel(_, idx)   => idx

        atIdx(idx + (onCtx.matchedCount - 1).max(0))(continuePassAtNextNode)

    extension [T](lhs: Manip[T])
      @scala.annotation.targetName("or")
      def |(rhs: Manip[T]): Manip[T] =
        semigroupK.combineK(lhs, rhs)
      def flatMap[U](using DebugInfo)(fn: T => Manip[U]): Manip[U] =
        pushRight(lhs): lhs =>
          Manip.FlatMap(lhs, t => commit(fn(t)))
      def lookahead: Lookahead[T] =
        Lookahead(lhs)
      def mkTable(elemsInit: Set[T]): Manip[T] =
        lhs match
          case Manip.Restrict(
                Manip.RefGet(ref, debugInfo),
                restriction,
                debugInfo2,
                continuation
              ) =>
            Manip.Table(
              ref,
              restriction,
              debugInfo ++ debugInfo2,
              elemsInit.iterator.map(_ -> continuation).toMap
            )
          case _ =>
            throw IllegalArgumentException(s"can't table $lhs")
      def restrict[U](using DebugInfo)(fn: PartialFunction[T, U]): Manip[U] =
        val fnRes = Restriction(fn)
        lhs match
          case Manip.Restrict(manip, restriction, debugInfo, Manip.Anchor()) =>
            Manip.Restrict(
              manip,
              restriction.combineRestrictions(fnRes),
              debugInfo ++ summon[DebugInfo],
              Manip.Anchor()
            )
          case _ =>
            pushRight(lhs): lhs =>
              Manip.Restrict(lhs, fnRes, summon[DebugInfo], Manip.Anchor())
      def filter(using DebugInfo)(pred: T => Boolean): Manip[T] =
        lhs.restrict:
          case v if pred(v) => v
      def withFinally(fn: => Unit): Manip[T] =
        Manip.Finally(lhs, () => fn)
      def withTracer(tracer: => Tracer): Manip[T] =
        tracerRef.init(tracer):
          getTracer.lookahead.flatMap: tracer =>
            lhs.withFinally(tracer.close())
      def tapEffect(fn: T => Unit): Manip[T] =
        lookahead.flatMap: value =>
          effect:
            fn(value)
            value

    extension (lhs: Manip[Node.All])
      def here[U](manip: Manip[U]): Manip[U] =
        lhs.lookahead.flatMap: node =>
          atNode(node)(manip)

    final class pass(
        strategy: pass.TraversalStrategy = pass.topDown,
        once: Boolean = false,
        wrapFn: Manip[Unit] => Manip[Unit] = identity
    ):
      def rules(using DebugInfo)(rules: pass.Ctx ?=> Rules): Manip[Unit] =
        val before = getTracer
          .product(Manip.GetRefMap)
          .flatMap: (tracer, refMap) =>
            effect(tracer.beforePass(summon[DebugInfo])(using refMap))
        val after = getTracer
          .product(Manip.GetRefMap)
          .flatMap: (tracer, refMap) =>
            effect(tracer.afterPass(summon[DebugInfo])(using refMap))

        lazy val loop: Manip[RulesResult] =
          commit:
            rules(using pass.Ctx(strategy, defer(loop)))
              | strategy.atNext(defer(loop))

        wrapFn:
          lazy val bigLoop: Manip[Unit] =
            pass.resultRef.init(RulesResult.NoProgress):
              (before *> loop <* after).flatMap:
                case RulesResult.Progress if !once =>
                  bigLoop
                case RulesResult.Progress | RulesResult.NoProgress =>
                  pure(())

          bigLoop

      def withState[T](ref: Ref[T])(init: => T): pass =
        pass(
          strategy = strategy,
          once = once,
          wrapFn = manip => ref.init(init)(defer(wrapFn(manip)))
        )
    end pass

    object pass:
      object resultRef extends Ref[RulesResult]

      final case class Ctx(
          strategy: TraversalStrategy,
          loop: Manip[RulesResult]
      ):
        lazy val loopAtNext: Manip[RulesResult] =
          strategy.atNext(loop)

      trait TraversalStrategy:
        def atNext(manip: Manip[RulesResult]): Manip[RulesResult]

      object topDown extends TraversalStrategy:
        private inline given DebugInfo = DebugInfo.notPoison

        def atNext(manip: Manip[RulesResult]): Manip[RulesResult] =
          val next = commit(manip)
          commit:
            atFirstChild(next)
              | atRightSibling(next)
              | (on(Pattern.ops.atEnd).check *> resultRef.get)
              *> atParent:
                commit:
                  atRightSibling(next)
                    | on(Pattern.ops.theTop).check *> resultRef.get

      // object bottomUp extends TraversalStrategy:
      //   def traverse(
      //       rules: Rules
      //   ): Manip[Boolean] =
      //     // TODO: fix this code, not tested and probably has similar bug to what topDown had
      //     def atBottomLeft[T](manip: Manip[T]): Manip[T] =
      //       lazy val impl: Manip[T] =
      //         atFirstChild(defer(impl))
      //           | manip

      //       impl

      //     lazy val impl: Manip[Boolean] =
      //       rules.flatMap: nextNode =>
      //         atNode(nextNode):
      //           impl.as(true)
      //       | atRightSibling:
      //         commit(defer(impl))
      //       | atParent:
      //         atRightSibling:
      //           atFirstChild:
      //             commit(defer(impl))
      //         | atFirstSibling:
      //           commit(defer(impl))
      //       | atParent(on(Pattern.ops.theTop).check.as(false))

      //     atBottomLeft(impl)
  end ops
end Manip
