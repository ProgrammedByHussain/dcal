package distcompiler

import cats.syntax.all.given

enum Manip[+T]:
  case Backtrack(debugInfo: DebugInfo)
  case Pure(value: T)
  case Ap[T, U](ff: Manip[T => U], fa: Manip[T]) extends Manip[U]
  case FlatMap[T, U](manip: Manip[T], fn: T => Manip[U]) extends Manip[U]
  case Effect(manip: Manip[T])

  case KeepLeft(left: Manip[T], right: Manip[?])
  case KeepRight(left: Manip[?], right: Manip[T])

  case Commit(manip: Manip[T], debugInfo: DebugInfo)

  case RefInit[T, U](ref: Manip.Ref[T], init: T, manip: Manip[U])
      extends Manip[U]
  case RefGet[T](ref: Manip.Ref[T], debugInfo: DebugInfo) extends Manip[T]
  case RefUpdated[T, U](
      ref: Manip.Ref[T],
      fn: T => T,
      manip: Manip[U],
      debugInfo: DebugInfo
  ) extends Manip[U]

  case ThisNode extends Manip[Node.All]
  case OnPattern[T](pattern: Pattern[T], debugInfo: DebugInfo)
      extends Manip[Pattern.Result.Accepted[T]]
  case AtNode(manip: Manip[T], node: Node.All)

  case Disjunction(first: Manip[T], second: Manip[T])
  case Deferred(fn: () => Manip[T])

  def perform(using DebugInfo)(top: Node.Top): T =
    import cats.Eval

    import Pattern.Result.*

    type Continue[-T, +U] = T => Eval[U]
    type Backtrack[+U] = DebugInfo => Eval[U]
    type RefMap = Map[ById[Manip.Ref[?]], Any]

    def emptyBacktrack(ctxInfo: DebugInfo, node: Node.All): Backtrack[Nothing] =
      posInfo =>
        throw RuntimeException(
          s"unrecovered backtrack at $posInfo, caught at $ctxInfo while looking at $node"
        )

    def impl[T, U](self: Manip[T])(using
        continue: Continue[T, U],
        backtrack: Backtrack[U],
        refMap: RefMap,
        node: Node.All
    ): Eval[U] =
      self match
        case Backtrack(debugInfo) => Eval.defer(backtrack(debugInfo))
        case Pure(value)          => Eval.defer(continue(value))
        case ap: Ap[t, u] =>
          given Continue[t => u, U] = ff =>
            given Continue[t, U] = fa => Eval.defer(continue(ff(fa)))
            Eval.defer(impl(ap.fa))
          impl(ap.ff)
        case flatMap: FlatMap[t, u] =>
          given Continue[t, U] = value =>
            given Continue[u, U] = continue
            Eval.defer(impl(flatMap.fn(value)))
          impl(flatMap.manip)
        case Effect(manip) => impl(manip)
        case KeepLeft(left, right: Manip[t]) =>
          given Continue[T, U] = value =>
            given Continue[t, U] = _ => continue(value)
            impl(right)
          impl(left)
        case KeepRight(left: Manip[t], right) =>
          given Continue[t, U] = _ =>
            given Continue[T, U] = continue
            Eval.defer(impl[T, U](right))
          impl(left)
        case Commit(manip, debugInfo) =>
          given Backtrack[U] = emptyBacktrack(debugInfo, node)
          impl(manip)
        case RefInit(ref, init, manip) =>
          given RefMap = refMap.updated(ById(ref), init)
          impl(manip)
        case RefGet(ref, debugInfo) =>
          refMap.get(ById(ref)) match
            case None        => Eval.defer(backtrack(debugInfo))
            case Some(value) => Eval.defer(continue(value.asInstanceOf[T]))
        case refUpdated: RefUpdated[t, T @unchecked] =>
          refMap.get(ById(refUpdated.ref)) match
            case None => Eval.defer(backtrack(refUpdated.debugInfo))
            case Some(value) =>
              given RefMap = refMap.updated(
                ById(refUpdated.ref),
                refUpdated.fn(value.asInstanceOf[t])
              )
              impl(refUpdated.manip)
        case ThisNode => Eval.defer(continue(node))
        case onPattern: OnPattern[t] =>
          onPattern.pattern.check(node) match
            case Rejected =>
              Eval.defer(backtrack(onPattern.debugInfo))
            case accepted: Accepted[`t`] =>
              Eval.defer(continue(accepted))
        case AtNode(manip, node) =>
          given Node.All = node
          impl(manip)
        case Disjunction(first, second) =>
          given Backtrack[U] = debugInfo1 =>
            given Backtrack[U] = debugInfo2 =>
              backtrack(debugInfo1 ++ debugInfo2)
            Eval.defer(impl(second))
          impl(first)
        case Deferred(fn) => impl(fn())

    given Continue[T, T] = Eval.now
    given Backtrack[T] = emptyBacktrack(summon[DebugInfo], top)
    given RefMap = Map.empty
    given Node.All = top
    impl[T, T](this).value
  end perform

  // TODO: optimize disjunctions to decision tries
end Manip

object Manip:
  trait Ref[T]:
    final def init[U](init: T)(manip: Manip[U]): Manip[U] =
      Manip.RefInit(this, init, manip)
    final def get(using DebugInfo): Manip[T] =
      Manip.RefGet(this, summon[DebugInfo])
    final def updated[U](using DebugInfo)(fn: T => T)(
        manip: Manip[U]
    ): Manip[U] = Manip.RefUpdated(this, fn, manip, summon[DebugInfo])

  type Rules = Manip[(Node.Sibling, Node.Sibling)]

  val unit: Manip[Unit] = ().pure
  export ops.defer
  export applicative.pure

  given applicative: cats.Applicative[Manip] with
    override def unit: Manip[Unit] = Manip.unit
    def ap[A, B](ff: Manip[A => B])(fa: Manip[A]): Manip[B] =
      Manip.Ap(ff, fa)
    def pure[A](x: A): Manip[A] = Manip.Pure(x)
    override def as[A, B](fa: Manip[A], b: B): Manip[B] =
      productR(fa)(pure(b))
    override def productL[A, B](fa: Manip[A])(fb: Manip[B]): Manip[A] =
      Manip.KeepLeft(fa, fb)
    override def productR[A, B](fa: Manip[A])(fb: Manip[B]): Manip[B] =
      Manip.KeepRight(fa, fb)

  given monoidK(using DebugInfo): cats.MonoidK[Manip] with
    def combineK[A](x: Manip[A], y: Manip[A]): Manip[A] =
      Manip.Disjunction(x, y)
    def empty[A]: Manip[A] = Manip.Backtrack(summon[DebugInfo])

  object ops:
    def defer[T](manip: => Manip[T]): Manip[T] =
      lazy val impl = manip
      Manip.Deferred(() => impl)

    def commit[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      Manip.Commit(manip, summon[DebugInfo])

    def atNode[T](node: Node.All)(manip: Manip[T]): Manip[T] =
      Manip.AtNode(manip, node)

    def atRightSibling[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      Manip.ThisNode.lookahead.flatMap:
        case thisChild: Node.Child =>
          atNode(thisChild)(manip)
        case _: (Node.Sentinel | Node.Root) =>
          Manip.Backtrack(summon[DebugInfo])

    def atFirstChild[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      Manip.ThisNode.lookahead.flatMap:
        case thisParent: Node.Parent =>
          atNode(thisParent.firstChild)(manip)
        case _: (Node.Sentinel | Node.Leaf) =>
          Manip.Backtrack(summon[DebugInfo])

    def atFirstSibling[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      Manip.ThisNode.lookahead.flatMap:
        case thisSibling: Node.Sibling =>
          atNode(thisSibling.parent.children.findSibling(0))(manip)
        case _: Node.Root =>
          Manip.Backtrack(summon[DebugInfo])

    def atParent[T](using DebugInfo)(manip: Manip[T]): Manip[T] =
      Manip.ThisNode.lookahead.flatMap:
        case thisChild: Node.Sibling =>
          atNode(thisChild.parent)(manip)
        case _: Node.Root =>
          Manip.Backtrack(summon[DebugInfo])

    def addChild[T](using DebugInfo)(child: => Node.Child): Manip[Node.Child] =
      Manip.ThisNode.effect:
        case thisParent: Node.Parent =>
          val tmp = child
          thisParent.children.addOne(tmp)
          pure(tmp)
        case _ => Manip.Backtrack(summon[DebugInfo])

    final class on[T](val pattern: Pattern[T]) extends AnyVal:
      def raw(using DebugInfo): Manip[Pattern.Result.Accepted[T]] =
        Manip.OnPattern(pattern, summon[DebugInfo])

      def value(using DebugInfo): Manip[T] =
        raw.map(_.value)

      def check(using DebugInfo): Manip[Unit] =
        raw.as(())

      def rewrite(using DebugInfo)(
          action: T => RewriteOp
      ): Manip[(Node.Sibling, Node.Sibling)] =
        raw.flatMap:
          case Pattern.Result.Accepted(value, matchedCount) =>
            Manip.ThisNode.effect: thisNode =>
              val replacementsOpt
                  : Manip.Backtrack[Nothing] | Skip.type | Iterable[
                    Node.Child
                  ] =
                action(value) match
                  case node: Node.Child => node :: Nil
                  case Splice(nodes*)   => nodes
                  case Delete           => Nil
                  case tn @ TryNext()   => Manip.Backtrack(tn.debugInfo)
                  case Skip             => Skip

              replacementsOpt match
                case Skip =>
                  thisNode match
                    case thisChild: Node.Child =>
                      (thisChild, thisChild.rightSibling).pure
                    case _: (Node.Sentinel | Node.Root) =>
                      throw RuntimeException(
                        "tried to continue at sentinel or root"
                      )
                case bt: Manip.Backtrack[Nothing] => bt
                case replacements: Iterable[Node.Child] =>
                  thisNode match
                    case thisSibling: Node.Sibling =>
                      val parent = thisSibling.parent
                      val startIdx = thisSibling.idxInParent
                      thisSibling.parent.children.patchInPlace(
                        startIdx,
                        replacements,
                        matchedCount
                      )
                      // two choices: stay where we are, or jump to the next untouched node
                      (
                        parent.children.findSibling(startIdx),
                        parent.children.findSibling(
                          startIdx + replacements.size
                        )
                      ).pure
                    case thisRoot: Node.Root =>
                      throw RuntimeException("tried to rewrite root node")

    extension [T](lhs: Manip[T])
      def |(rhs: Manip[T]): Manip[T] =
        Manip.Disjunction(lhs, rhs)
      def flatMap[U](using DebugInfo)(fn: T => Manip[U]): Manip[U] =
        Manip.FlatMap(lhs, t => commit(fn(t)))
      def effect[U](fn: T => Manip[U]): Manip[U] =
        lhs.flatMap(fn)
      def lookahead: Lookahead[T] =
        Lookahead(lhs)

    extension (lhs: Manip[Node.All])
      def here[U](manip: Manip[U]): Manip[U] =
        lhs.lookahead.flatMap: node =>
          atNode(node)(manip)

    class Lookahead[T](val manip: Manip[T]) extends AnyVal:
      def flatMap[U](fn: T => Manip[U]): Manip[U] =
        Manip.FlatMap(manip, fn)
      def effect[U](fn: T => Manip[U]): Manip[U] =
        flatMap(t => Manip.Effect(fn(t)))

    object Lookahead:
      given applicative: cats.Applicative[Lookahead] with
        def ap[A, B](ff: Lookahead[A => B])(fa: Lookahead[A]): Lookahead[B] =
          Lookahead(ff.manip.ap(fa.manip))
        def pure[A](x: A): Lookahead[A] =
          Lookahead(Manip.Pure(x))
      given monoidK(using DebugInfo): cats.MonoidK[Lookahead] with
        def combineK[A](x: Lookahead[A], y: Lookahead[A]): Lookahead[A] =
          Lookahead(x.manip.combineK(y.manip))
        def empty[A]: Lookahead[A] =
          Lookahead(Manip.Backtrack(summon[DebugInfo]))

    final case class Splice(nodes: Node.Child*)
    case object Delete
    final case class TryNext()(using val debugInfo: DebugInfo)
    case object Skip

    type RewriteOp =
      Node.Child | Splice | Delete.type | TryNext | Skip.type

    // TODO: debug info as implicit param.
    // - optionally print AST after pass
    // - for initial AST, give direct option to print that in reader
    // - include link to file + line num in printed info
    // - option to print after every edit?
    // - don't discount ability to make debug adapter for this later...

    final class pass(
        strategy: pass.TraversalStrategy = pass.topDown,
        once: Boolean = false
    ):
      def rules(rules: Manip[(Node.Sibling, Node.Sibling)]): Manip[Unit] =
        lazy val impl: Manip[Unit] =
          strategy
            .traverse(rules)
            .flatMap: madeChange =>
              if madeChange && !once
              then impl
              else Manip.unit

        impl
    end pass

    object pass:
      trait TraversalStrategy:
        def traverse(rules: Manip[(Node.Sibling, Node.Sibling)]): Manip[Boolean]

      object topDown extends TraversalStrategy:
        def traverse(
            rules: Manip[(Node.Sibling, Node.Sibling)]
        ): Manip[Boolean] =
          lazy val impl: Manip[Boolean] =
            commit:
              rules.flatMap: (_, nextSibling) =>
                commit:
                  atNode(nextSibling):
                    impl.as(true)
              | atFirstChild(defer(impl))
                | atRightSibling(defer(impl))
                | atParent(atRightSibling(defer(impl)))
                | atParent(on(Pattern.ops.theTop).check.as(false))

          impl

      object bottomUp extends TraversalStrategy:
        def traverse(
            rules: Manip[(Node.Sibling, Node.Sibling)]
        ): Manip[Boolean] =
          def atBottomLeft[T](manip: Manip[T]): Manip[T] =
            lazy val impl: Manip[T] =
              atFirstChild(defer(impl))
                | manip

            impl

          lazy val impl: Manip[Boolean] =
            rules.flatMap: (_, nextSibling) =>
              atNode(nextSibling):
                impl.as(true)
            | atRightSibling:
              commit(defer(impl))
            | atParent:
              atRightSibling:
                atFirstChild:
                  commit(defer(impl))
              | atFirstSibling:
                commit(defer(impl))
            | false.pure

          atBottomLeft(impl)
  end ops
end Manip
