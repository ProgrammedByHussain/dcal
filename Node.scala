package distcompiler

import cats.Eval
import cats.data.Chain
import cats.syntax.all.given
import scala.collection.mutable
import izumi.reflect.Tag
import scala.annotation.constructorOnly

final case class NodeError(msg: String) extends RuntimeException(msg)

final class Node(val token: Token)(
    childrenInit: IterableOnce[Node.Child] @constructorOnly = Nil
) extends Node.Child,
      Node.Parent(childrenInit),
      Node.Traversable:
  thisNode =>

  assertErrorRefCounts()

  override def asNode: Node = this

  override def asNonFloatingParent: Node | Node.Top = this

  override type This = Node
  override def cloneEval(): Eval[Node] =
    Chain
      .traverseViaChain(children.toIndexedSeq)(_.cloneEval())
      .map(clonedChildren => Node(token)(clonedChildren.toIterable).like(this))

  private var _sourceRange: SourceRange | Null = null

  def extendLocation(sourceRange: SourceRange): this.type =
    if _sourceRange eq null
    then _sourceRange = sourceRange
    else _sourceRange = _sourceRange.nn <+> sourceRange
    this

  def at(string: String): this.type =
    at(Source.fromString(string))

  def at(source: Source): this.type =
    at(SourceRange.entire(source))

  def at(sourceRange: SourceRange): this.type =
    if _sourceRange eq null
    then
      _sourceRange = sourceRange
      this
    else throw NodeError("node source range already set")

  def like(other: Node): this.type =
    if other._sourceRange eq null
    then this
    else at(other._sourceRange.nn)

  def sourceRange: SourceRange =
    var rangeAcc: SourceRange | Null = null
    traverse:
      case node: Node =>
        if node._sourceRange ne null
        then
          if rangeAcc ne null
          then rangeAcc = rangeAcc.nn <+> node._sourceRange.nn
          else rangeAcc = node._sourceRange
          Node.TraversalAction.SkipChildren
        else Node.TraversalAction.Continue
      case _: Node.Embed[?] =>
        Node.TraversalAction.SkipChildren

    if rangeAcc ne null
    then rangeAcc.nn
    else SourceRange.entire(Source.empty)

  private var _scopeRelevance: Int =
    if token.canBeLookedUp then 1 else 0

  private var _errorRefCount: Int =
    if token == Builtin.Error
    then 1
    else 0

  override def unparent(): this.type =
    require(parent.nonEmpty)
    if _scopeRelevance > 0
    then parent.get.decScopeRelevance()
    if _errorRefCount > 0
    then parent.get.decErrorRefCount()

    super.unparent()

  override def ensureParent(parent: Node.Parent, idxInParent: Int): this.type =
    val prevParent = this.parent
    super.ensureParent(parent, idxInParent)

    // We can ensureParent without unparent() if only the idx changes.
    // In that case, don't inc twice.
    if _scopeRelevance > 0 && (prevParent ne parent)
    then parent.incScopeRelevance()
    if _errorRefCount > 0 && (prevParent ne parent)
    then parent.incErrorRefCount()

    this

  def assertErrorRefCounts(): Unit =
    val countErrors = children.iterator.filter(_.hasErrors).size
    assert(countErrors == _errorRefCount,
      s"mismatched error counts, $countErrors != $_errorRefCount")

  override def hasErrors: Boolean =
    val count1 = _errorRefCount > 0
    val count2 = errors.nonEmpty
    assert(count1 == count2, s"mismatched hasErrors $count1 != $count2")
    count1

  override def errors: List[Node] =
    val errorsAcc = mutable.ListBuffer.empty[Node]
    traverse:
      case thisNode: Node if thisNode.token == Builtin.Error =>
        errorsAcc += thisNode
        Node.TraversalAction.SkipChildren
      
      case _: (Node | Node.Embed[?]) => Node.TraversalAction.Continue

    errorsAcc.result()

  // def iteratorDescendants: Iterator[Node.Child] =
  //   Iterator
  //     .unfold(firstChild):
  //       case sentinel: Node.RightSiblingSentinel =>
  //         sentinel.parent match
  //           case root: Node.Root => None
  //           case myself if myself eq thisNode =>
  //             None // don't climb up beyond where we started
  //           case parentNode: Node =>
  //             Some((None, parentNode.rightSibling))
  //       case node: Node => Some((Some(node), node.firstChild))
  //       case leaf: (Node.Leaf & Node.Child) =>
  //         Some((Some(leaf), leaf.rightSibling))
  //     .flatten

  def lookup: List[Node] =
    assert(token.canBeLookedUp)
    parent.map(_.findNodeByKey(thisNode)).getOrElse(Nil)

  def lookupRelativeTo(referencePoint: Node): List[Node] =
    assert(token.canBeLookedUp)
    referencePoint.findNodeByKey(thisNode)

  override def lookupKeys: Set[Node] =
    this.inspect(token.lookedUpBy).getOrElse(Set.empty)
end Node

object Node:
  enum TraversalAction:
    case SkipChildren, Continue
  end TraversalAction

  sealed trait Traversable:
    thisTraversable =>

    final def traverse(fn: Node.Child => Node.TraversalAction): Unit =
      extension (self: Node.Child) def findNextChild: Option[Node.Child] =
        var curr = self
        def extraConditions: Boolean =
          curr.parent.nonEmpty
          // .rightSibling looks at the parent node. If we're looking at thisTraversable,
          // then that means we should stop or we'll be traversing our parent's siblings.
          && (curr ne thisTraversable)
          && !curr.parent.get.isInstanceOf[Node.Top]

        while
          curr.rightSibling.isEmpty
          && extraConditions
        do
          curr = curr.parent.get.asNode

        if !extraConditions
        then None
        else curr.rightSibling
    
      @scala.annotation.tailrec
      def impl(traversable: Node.Child): Unit =
        traversable match
          case node: Node =>
            import TraversalAction.*
            fn(node) match
              case SkipChildren =>
                node.findNextChild match
                  case None =>
                  case Some(child) => impl(child)
              case Continue =>
                node.firstChild match
                  case None =>
                    node.findNextChild match
                      case None =>
                      case Some(child) => impl(child)
                  case Some(child) => impl(child)
          case embed: Embed[?] => fn(embed)

      this match
        case thisChild: Node.Child => impl(thisChild)
        case thisTop: Node.Top =>
          thisTop.children.iterator
            .foreach(impl)
  end Traversable

  sealed trait All extends Cloneable:
    type This <: All
    def cloneEval(): Eval[This]

    final override def clone(): This =
      cloneEval().value

    final def inspect[T](pattern: Pattern[T]): Option[T] =
      import dsl.*
      (pattern.map(Some(_)) | Pattern.pure(None)).manip.perform(this)._2

    def asNode: Node =
      throw NodeError("not a node")

    def asTop: Top =
      throw NodeError("not a top")

    def asChild: Child =
      throw NodeError("not a child")

    def isChild: Boolean = false

    def hasErrors: Boolean

    def errors: List[Node]

    def parent: Option[Node.Parent] = None

    def idxInParent: Int =
      throw NodeError("tried to get index in node that cannot have a parent")

    final override def hashCode(): Int =
      this match
        case node: Node =>
          (Node, node.token, node.children).hashCode()
        case top: Top =>
          (Top, top.children).hashCode()
        case Embed(value) =>
          (Embed, value).hashCode()

    final override def equals(that: Any): Boolean =
      if this eq that.asInstanceOf[AnyRef]
      then return true
      (this, that) match
        case (thisNode: Node, thatNode: Node) =>
          thisNode.token == thatNode.token
          && (if thisNode.token.showSource
              then thisNode.sourceRange == thatNode.sourceRange
              else true)
          && thisNode.children == thatNode.children
        case (thisTop: Top, thatTop: Top) =>
          thisTop.children == thatTop.children
        case (thisEmbed: Embed[?], thatEmbed: Embed[?]) =>
          thisEmbed.value == thatEmbed.value
        case _ => false

    final override def toString(): String =
      this match
        case node: Node =>
          val locPart =
            if node.token.showSource
            then s"\"${node.sourceRange.decodeString()}\""
            else ""
          s"${node.token.name}@${System.identityHashCode(node)}$locPart(${node.children.mkString(", ")})"
        case top: Top =>
          s"Top(${top.children.mkString(", ")})"
        case Embed(value) =>
          s"Embed($value)"

  sealed trait Root extends All:
    override type This <: Root

  final class Top(childrenInit: IterableOnce[Node.Child] @constructorOnly)
      extends Root,
        Parent(childrenInit),
        Traversable:
    def this(childrenInit: Node.Child*) = this(childrenInit)

    override def asTop: Top = this

    override def asNonFloatingParent: Node | Top = this

    override type This = Top
    override def cloneEval(): Eval[Top] =
      Chain
        .traverseViaChain(children.toIndexedSeq)(_.cloneEval())
        .map(_.toIterable)
        .map(Top(_))

    override def hasErrors: Boolean = children.exists(_.hasErrors)
    override def errors: List[Node] =
      children
        .iterator
        .filter(_.hasErrors)
        .flatMap(_.errors)
        .toList

    def serializedBy(wf: Wellformed): Top =
      val result = clone()
      wf.serializeTree.perform(result)
      result

    def toPrettyWritable(wf: Wellformed): geny.Writable =
      sexpr.serialize.toPrettyWritable(serializedBy(wf))

    def toCompactWritable(wf: Wellformed): geny.Writable =
      sexpr.serialize.toCompactWritable(serializedBy(wf))

    def toPrettyString(wf: Wellformed): String =
      sexpr.serialize.toPrettyString(serializedBy(wf))
  end Top

  object Top

  sealed trait Parent(
      childrenInit: IterableOnce[Node.Child] @constructorOnly
  ) extends All:
    thisParent =>

    def asNonFloatingParent: Node | Top

    override type This <: Parent

    val children: Node.Children =
      Node.Children(thisParent, childrenInit)

    final def apply(tok: Token): Node =
      val results = children.iterator
        .collect:
          case node: Node if node.token == tok =>
            node
        .toList

      require(results.size == 1)
      results.head

    final def firstChild: Option[Node.Child] =
      children.headOption

    @scala.annotation.tailrec
    private[Node] final def incScopeRelevance(): Unit =
      this match
        case root: Node.Root => // nothing to do here
        case thisNode: Node =>
          thisNode._scopeRelevance += 1
          if thisNode._scopeRelevance == 1
          then thisNode.parent match
            case None =>
            case Some(parent) => parent.incScopeRelevance()

    @scala.annotation.tailrec
    private[Node] final def decScopeRelevance(): Unit =
      this match
        case root: Node.Root => // nothing to do here
        case thisNode: Node =>
          assert(thisNode._scopeRelevance > 0)
          thisNode._scopeRelevance -= 1
          if thisNode._scopeRelevance == 0
          then thisNode.parent match
            case None =>
            case Some(parent) => parent.decScopeRelevance()

    @scala.annotation.tailrec
    private [Node] final def incErrorRefCount(): Unit =
      this match
        case root: Node.Root => // nothing to do here
        case thisNode: Node =>
          thisNode._errorRefCount += 1
          if thisNode._errorRefCount == 1
          then thisNode.parent match
            case None =>
            case Some(parent) => parent.incErrorRefCount()

    @scala.annotation.tailrec
    private[Node] final def decErrorRefCount(): Unit =
      this match
        case root: Node.Root => // nothing to do here
        case thisNode: Node =>
          // assert(thisNode._errorRefCount > 0,
          //   "???")
          thisNode._errorRefCount -= 1
          if thisNode._errorRefCount == 0
          then thisNode.parent match
            case None =>
            case Some(parent) => parent.decErrorRefCount()

    @scala.annotation.tailrec
    private[Node] final def findNodeByKey(key: Node): List[Node] =
      this match
        case root: Node.Root => Nil
        case thisNode: Node
            if thisNode.token.symbolTableFor.contains(key.token) =>
          import Node.TraversalAction.*
          val resultsList = mutable.ListBuffer.empty[Node]
          thisNode.traverseChildren:
            case irrelevantNode: Node if irrelevantNode._scopeRelevance == 0 =>
              SkipChildren
            case descendantNode: Node =>
              if !descendantNode.token.canBeLookedUp
              then TraversalAction.Continue
              else
                descendantNode.inspect(descendantNode.token.lookedUpBy) match
                  case None => TraversalAction.Continue
                  case Some(descendantKey) =>
                    if key == descendantKey
                    then resultsList.addOne(descendantNode)
                    TraversalAction.Continue
            case _: Node.Embed[?] => SkipChildren

          resultsList.result()
        case thisNode: Node =>
          thisNode.parent match
            case None => Nil
            case Some(parent) => parent.findNodeByKey(key)

    final def traverseChildren(fn: Node.Child => TraversalAction): Unit =
      children.iterator.foreach(_.traverse(fn))

    final def children_=(childrenInit: IterableOnce[Node.Child]): Unit =
      children.clear()
      children.addAll(childrenInit)

    final def unparentedChildren: IterableOnce[Node.Child] =
      // .unparent() is done by Children .clear
      val result = children.toArray
      children.clear()
      result
  end Parent

  sealed trait Child extends Traversable, All:
    thisChild =>

    override type This <: Child

    override def isChild: Boolean = true

    override def asChild: Child = this

    private var _parent: Parent | Null = null
    private var _idxInParent: Int = -1

    def ensureParent(parent: Parent, idxInParent: Int): this.type =
      if _parent eq parent
      then
        // reparenting within the same parent shouldn't really do anything,
        // so don't make a fuss if it happens. The seq ops on Children might do this.
        _idxInParent = idxInParent
        this
      else
        if _parent eq null
        then
          _parent = parent
          _idxInParent = idxInParent
          this
        else
          throw NodeError("node already has a parent")

    def unparent(): this.type =
      assert(_parent ne null, "tried to unparent floating node")
      _parent = null
      _idxInParent = -1
      this

    override def parent: Option[Parent] =
      _parent match
        case null => None
        case _parent: Parent => Some(_parent)

    override def idxInParent: Int =
      if _parent eq null
      then throw NodeError("tried to get index in parent of floating node")
      assert(_idxInParent >= 0)
      _idxInParent

    def lookupKeys: Set[Node] = Set.empty

    final def replaceThis(replacement: => Node.Child): Node.Child =
      val parentTmp = parent.get
      val idxInParentTmp = idxInParent
      val computedReplacement = replacement
      parentTmp.children(idxInParentTmp) = computedReplacement
      computedReplacement

    final def rightSibling: Option[Node.Child] =
      parent.flatMap: parent =>
        parent.children.lift(idxInParent + 1)
  end Child

  final case class Embed[T](value: T)(using val nodeMeta: NodeMeta[T])
      extends Child:
    override type This = Embed[T]
    override def cloneEval(): Eval[Embed[T]] =
      Eval.now(Embed(nodeMeta.doClone(value)))

    override def hasErrors: Boolean = false
    override def errors: List[Node] = Nil
  end Embed

  final class Children private[Node] (
      val parent: Node.Parent,
      childrenInit: IterableOnce[Node.Child] @constructorOnly
  ) extends mutable.IndexedBuffer[Node.Child]:
    private val _children = mutable.ArrayBuffer.from(childrenInit)
    _children.iterator.zipWithIndex.foreach: (child, idx) =>
      child.ensureParent(parent, idx)
    export _children.{length, apply}

    // TODO: assert error refcounts every time something changes
    private def assertErrorRefCounts(): Unit =
      parent match
        case thisNode: Node => thisNode.assertErrorRefCounts()
        case _ =>

    private def reIdxFromIdx(idx: Int): this.type =
      var curr = idx
      while curr < length
      do
        _children(curr).ensureParent(parent, curr)
        curr += 1
      this

    // can't export due to redef rules
    override def knownSize: Int = _children.knownSize

    def ensureWithKey(elem: Node): this.type =
      val keys = elem.lookupKeys
      if !_children.exists(_.lookupKeys.intersect(keys).nonEmpty)
      then addOne(elem)
      this

    override def prepend(elem: Node.Child): this.type =
      _children.prepend(elem.ensureParent(parent, 0))
      reIdxFromIdx(1)
      assertErrorRefCounts()
      this

    override def insert(idx: Int, elem: Node.Child): Unit =
      _children.insert(idx, elem.ensureParent(parent, idx))
      reIdxFromIdx(idx + 1)
      assertErrorRefCounts()

    //@scala.annotation.tailrec
    override def insertAll(idx: Int, elems: IterableOnce[Node.Child]): Unit =
      elems match
        case elems: Iterable[Node.Child] =>
          // Keeping this separate allows ensureParent to fail without corrupting the structure.
          elems.iterator.zipWithIndex
            .foreach: (child, childIdx) =>
              child.ensureParent(parent, idx + childIdx)

          _children.insertAll(idx, elems)
          reIdxFromIdx(idx + elems.size)
        case elems => insertAll(idx, mutable.ArrayBuffer.from(elems))

      assertErrorRefCounts()

    override def remove(idx: Int): Node.Child =
      val child = _children.remove(idx).unparent()
      reIdxFromIdx(idx)
      assertErrorRefCounts()
      child

    override def remove(idx: Int, count: Int): Unit =
      (idx until idx + count).foreach: childIdx =>
        _children(childIdx).unparent()
      _children.remove(idx, count)
      reIdxFromIdx(idx)
      assertErrorRefCounts()

    override def clear(): Unit =
      _children.foreach(_.unparent())
      _children.clear()
      assertErrorRefCounts()

    override def addOne(elem: Child): this.type =
      _children.addOne(elem.ensureParent(parent, length))
      assertErrorRefCounts()
      this

    override def update(idx: Int, child: Node.Child): Unit =
      val existingChild = _children(idx)
      if existingChild ne child
      then
        child.ensureParent(parent, idx)
        existingChild.unparent()
        _children(idx) = child
      assertErrorRefCounts()

    override def iterator: Iterator[Node.Child] =
      (0 until length).iterator.map(this)
  end Children
end Node

trait NodeMeta[T](using val tag: Tag[T]):
  extension (self: T) def asNode: Node

  def doClone(self: T): T

object NodeMeta:
  given forToken: NodeMeta[Token] with
    extension (self: Token) def asNode: Node = self()

    def doClone(self: Token): Token = self

  final class byToString[T: Tag] extends NodeMeta[T]:
    def doClone(self: T): T = self
    extension (self: T)
      def asNode: Node =
        byToString.mkNode().at(self.toString())

  object byToString extends Token
