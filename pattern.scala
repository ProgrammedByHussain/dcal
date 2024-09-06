package distcompiler

import scala.collection.mutable

enum Pattern[+T]:
  case ThisToken(token: Token) extends Pattern[Node]
  case Choose[T](first: Pattern[T], second: Pattern[T]) extends Pattern[T]
  case Adjacent[T1, T2](first: Pattern[T1], second: Pattern[T2])
      extends Pattern[(T1, T2)]
  case And[T1, T2](first: Pattern[T1], second: Pattern[T2])
      extends Pattern[(T1, T2)]

  // subsumes all of parent, child, lookup, lookdown, etc
  // e.g., for looking at parent, you pick Any, you filter .hasParent, and you use (.parent, .indexInParent)
  // ... same for any other transformation where you look at a node, somehow find more nodes, and want to match on them
  case ForThis[T](srcPattern: Pattern[(Node, Int)], pattern: Pattern[T])
      extends Pattern[T]
  case ForAny[T](
      srcPattern: Pattern[IterableOnce[(Node, Int)]],
      pattern: Pattern[T]
  ) extends Pattern[List[T]]
  case ForAll[T](
      srcPattern: Pattern[IterableOnce[(Node, Int)]],
      pattern: Pattern[T]
  ) extends Pattern[List[T]]

  case AnyToken extends Pattern[Node]
  case End extends Pattern[Unit]
  case Repeated[T](pattern: Pattern[T]) extends Pattern[List[T]]

  case Find[T](pattern: Pattern[T]) extends Pattern[(Int, T)]

  case Lazy[T](patternFn: () => Pattern[T]) extends Pattern[T]

  case Map[T1, T2](pattern: Pattern[T1], fn: T1 => T2) extends Pattern[T2]
  case Filter[T](pattern: Pattern[T], pred: T => Boolean) extends Pattern[T]

  case Negated(pattern: Pattern[?]) extends Pattern[Unit]

  @scala.annotation.alpha("or")
  def |[U >: T](other: Pattern[U]): Pattern[U] =
    Choose(this, other)

  @scala.annotation.alpha("and")
  def &[U](other: Pattern[U]): Pattern[(T, U)] =
    And(this, other)

  def map[U](fn: T => U): Pattern[U] =
    Map(this, fn)

  def filter(pred: T => Boolean): Pattern[T] =
    Filter(this, pred)

  def inspect[U](using T <:< Node)(pattern: Pattern[U]): Pattern[U] =
    ForThis(
      srcPattern = this.map: instance =>
        (instance.parent, instance.idxInParent),
      pattern = pattern
    )

  def children[U](using T <:< Node)(pattern: Pattern[U]): Pattern[U] =
    ForThis(this.map((_, 0)), pattern)

  def apply(parent: Node, startIdx: Int): Pattern.Result[T] =
    import Pattern.Result
    import Result.*

    def impl[T](self: Pattern[T], startIdx: Int)(using
        parent: Node
    ): Pattern.Result[T] =
      def withValid[T](body: => Result[T]): Result[T] =
        if parent.children.isDefinedAt(startIdx)
        then body
        else Rejected

      def withNode[T](fn: Node => Result[T]): Result[T] =
        withValid:
          parent.children(startIdx) match
            case node: Node    => fn(node)
            case Node.Embed(_) => Rejected

      self match
        case ThisToken(token) =>
          withNode: node =>
            if node.token == token
            then Matched(1, node)
            else Rejected
        case Choose(first, second) =>
          impl(first, startIdx) match
            case m @ Matched(_, _) => m
            case Rejected =>
              impl(second, startIdx)

        case Adjacent(first, second) =>
          impl(first, startIdx) match
            case Matched(length, bound1) =>
              impl(second, startIdx + length) match
                case Matched(length2, bound2) =>
                  Matched(length + length2, (bound1, bound2))
                case Rejected =>
                  Rejected
            case Rejected => Rejected

        case And(first, second) =>
          impl(first, startIdx) match
            case Matched(length1, bound1) =>
              impl(second, startIdx) match
                case Matched(length2, bound2) =>
                  Matched(length1.max(length2), (bound1, bound2))
                case Rejected => Rejected

            case Rejected => Rejected

        case ForThis(srcPattern, pattern) =>
          impl(srcPattern, startIdx) match
            case Matched(length, (parent, idxInParent)) =>
              impl(pattern, idxInParent)(using parent) match
                case Matched(_, bound) => Matched(length, bound)
                case Rejected          => Rejected
            case Rejected => Rejected

        case ForAny(srcPattern, pattern) =>
          impl(srcPattern, startIdx) match
            case Matched(length, pairs) =>
              val elems =
                pairs.iterator
                  .map:
                    case (parent, idxInParent) =>
                      impl(pattern, idxInParent)(using parent)
                  .collect:
                    case Matched(_, bound) => bound
                  .toList
              Matched(length, elems)
            case Rejected => Rejected

        case ForAll(srcPattern, pattern: Pattern[t]) =>
          impl(srcPattern, startIdx) match
            case Matched(length, pairs) =>
              val elems =
                pairs.iterator
                  .map:
                    case (parent, idxInParent) =>
                      impl(pattern, idxInParent)(using parent)
                  .toList

              if elems.forall:
                  case _: Matched[t] => true
                  case Rejected      => false
              then
                Matched(
                  length,
                  elems.collect { case Matched(_, bound) => bound }
                )
              else Rejected
            case Rejected => Rejected

        case AnyToken =>
          withNode(Matched(1, _))
        case End =>
          if !parent.children.isDefinedAt(startIdx)
          then
            assert(startIdx == parent.children.length)
            Matched(0, ())
          else Rejected
        case Repeated(pattern: Pattern[t]) =>
          val buf = mutable.ListBuffer.empty[t]
          @scala.annotation.tailrec
          def repeatedImpl(startIdx: Int, length: Int): Result[T] =
            impl(pattern, startIdx) match
              case Matched(length, bound) =>
                buf += bound
                assert(length > 0) // or we cause an infinite loop
                repeatedImpl(startIdx + length, length + 1)
              case Rejected =>
                Matched(length, buf.toList)

          repeatedImpl(startIdx, length = 0)

        case Find(pattern: Pattern[t]) =>
          def findImpl(startIdx: Int, howManySkipped: Int): Result[(Int, t)] =
            if startIdx == parent.children.length
            then
              impl(pattern, startIdx) match
                case Matched(length, bound) =>
                  Matched(length + howManySkipped, (howManySkipped, bound))
                case Rejected => Rejected
            else
              impl(pattern, startIdx) match
                case Matched(length, bound) =>
                  Matched(length + howManySkipped, (howManySkipped, bound))
                case Rejected => findImpl(startIdx + 1, howManySkipped + 1)

          findImpl(startIdx, howManySkipped = 0)

        case Lazy(patternFn)  => impl(patternFn(), startIdx)
        case Map(pattern, fn) => impl(pattern, startIdx).map(fn)
        case Filter(pattern, pred) =>
          impl(pattern, startIdx) match
            case m @ Matched(length, bound) =>
              if pred(bound)
              then m
              else Rejected
            case Rejected => Rejected

        case Negated(pattern) =>
          impl(pattern, startIdx) match
            case Matched(_, _) => Rejected
            case Rejected      => Matched(0, ())

    impl(this, startIdx)(using parent)
end Pattern

object Pattern:
  def tok(token: Token): Pattern[Node] =
    ThisToken(token)

  def find[T](pattern: Pattern[T]): Pattern[T] =
    Find(pattern).map(_._2)

  given adjacentTuple[PatternTpl <: NonEmptyTuple]
      : Conversion[PatternTpl, Pattern[Tuple.InverseMap[PatternTpl, Pattern]]]
  with
    def apply(tpl: PatternTpl): Pattern[Tuple.InverseMap[PatternTpl, Pattern]] =
      tpl.productIterator
        .asInstanceOf[Iterator[Pattern[Any]]]
        .foldLeft(None: Option[Pattern[NonEmptyTuple]]): (acc, elem) =>
          acc match
            case None             => Some(elem.map(Tuple1.apply))
            case Some(accPattern) => Some((accPattern & elem).map(_ :* _))
        .get
        .asInstanceOf

  enum Result[+T]:
    case Matched[T](length: Int, bound: T) extends Result[T]
    case Rejected

    def map[U](fn: T => U): Result[U] =
      this match
        case Matched(length, bound) => Matched(length, fn(bound))
        case Rejected               => Rejected

  end Result
end Pattern
