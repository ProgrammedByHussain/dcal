package distcompiler.tla

import distcompiler.*

object Operators:
  sealed trait Operator extends Token, Product:
    def spelling: String = productPrefix

  object Operator:
    lazy val instances: IArray[Operator] =
      PrefixOperator.instances
        ++ InfixOperator.instances
        ++ PostfixOperator.instances

  sealed trait PrefixOperator(val lowPrecedence: Int, val highPrecedence: Int)
      extends Operator
  object PrefixOperator extends util.HasInstanceArray[PrefixOperator]

  case object `ENABLED` extends PrefixOperator(4, 15)
  case object `SUBSET` extends PrefixOperator(8, 8)
  case object `DOMAIN` extends PrefixOperator(9, 9)
  case object `[]` extends PrefixOperator(4, 15)
  case object `\\neg` extends PrefixOperator(4, 4)
  case object `~` extends PrefixOperator(4, 4)
  case object `UNION` extends PrefixOperator(8, 8)
  case object `<>` extends PrefixOperator(4, 15)
  case object `\\lnot` extends PrefixOperator(4, 4)
  case object `-_` extends PrefixOperator(12, 12)
  case object `UNCHANGED` extends PrefixOperator(4, 15)

  sealed trait InfixOperator(
      val lowPrecedence: Int,
      val highPredecence: Int,
      val isAssociative: Boolean = false
  ) extends Operator
  object InfixOperator extends util.HasInstanceArray[InfixOperator]

  case object `\\cong` extends InfixOperator(5, 5)
  case object `\\cdot` extends InfixOperator(5, 14, true)
  case object `\\sqsubseteq` extends InfixOperator(5, 5)
  case object `\\bullet` extends InfixOperator(13, 13, true)
  case object `**` extends InfixOperator(13, 13, true)
  case object `^^` extends InfixOperator(14, 14)
  case object `/\\` extends InfixOperator(3, 3, true)
  case object `|=` extends InfixOperator(5, 5)
  case object `\\succeq` extends InfixOperator(5, 5)
  case object `\\oslash` extends InfixOperator(13, 13)
  case object `\\sqcap` extends InfixOperator(9, 13, true)
  case object `*` extends InfixOperator(13, 13, true)
  case object `<=` extends InfixOperator(5, 5)
  case object `\\approx` extends InfixOperator(5, 5)
  case object `\\equiv` extends InfixOperator(2, 2)
  case object `%` extends InfixOperator(10, 11)
  case object `/=` extends InfixOperator(5, 5)
  case object `\\lor` extends InfixOperator(3, 3)
  case object `\\in` extends InfixOperator(5, 5)
  case object `\\div` extends InfixOperator(13, 13)
  case object `:>` extends InfixOperator(7, 7)
  case object `.` extends InfixOperator(17, 17, true)
  case object `\\asymp` extends InfixOperator(5, 5)
  case object `=` extends InfixOperator(5, 5)
  case object `\\prec` extends InfixOperator(5, 5)
  case object `\\circ` extends InfixOperator(13, 13, true)
  case object `\\succ` extends InfixOperator(5, 5)
  case object `\\simeq` extends InfixOperator(5, 5)
  case object `<` extends InfixOperator(5, 5)
  case object `\\notin` extends InfixOperator(5, 5)
  case object `::=` extends InfixOperator(5, 5)
  case object `\\cap` extends InfixOperator(8, 8, true)
  case object `\\ominus` extends InfixOperator(11, 11, true)
  case object `-|` extends InfixOperator(5, 5)
  case object `&` extends InfixOperator(13, 13, true)
  case object `=|` extends InfixOperator(5, 5)
  case object `|-` extends InfixOperator(5, 5)
  case object `\\` extends InfixOperator(8, 8)
  case object `=<` extends InfixOperator(5, 5)
  case object `(-)` extends InfixOperator(11, 11)
  case object `\\union` extends InfixOperator(8, 8, true)
  case object `>=` extends InfixOperator(5, 5)
  case object `=>` extends InfixOperator(1, 1)
  case object `\\leq` extends InfixOperator(5, 5)
  case object `\\propto` extends InfixOperator(5, 5)
  case object `\\sqcup` extends InfixOperator(9, 13, true)
  case object `||` extends InfixOperator(10, 11, true)
  case object `~>` extends InfixOperator(2, 2)
  case object `|` extends InfixOperator(10, 11, true)
  case object `\\odot` extends InfixOperator(13, 13, true)
  case object `\\sim` extends InfixOperator(5, 5)
  case object `\\o` extends InfixOperator(13, 13, true)
  case object `\\sqsupseteq` extends InfixOperator(5, 5)
  case object `-` extends InfixOperator(11, 11, true)
  case object `<=>` extends InfixOperator(5, 5)
  case object `@@` extends InfixOperator(6, 6, true)
  case object `??` extends InfixOperator(9, 13, true)
  case object `\\oplus` extends InfixOperator(10, 10, true)
  case object `\\land` extends InfixOperator(3, 3)
  case object `\\bigcirc` extends InfixOperator(13, 13)
  case object `++` extends InfixOperator(10, 10, true)
  case object `\\subset` extends InfixOperator(5, 5)
  case object `#` extends InfixOperator(5, 5)
  case object `\\subseteq` extends InfixOperator(5, 5)
  case object `..` extends InfixOperator(9, 9)
  case object `\\/` extends InfixOperator(3, 3, true)
  case object `\\supseteq` extends InfixOperator(5, 5)
  case object `\\uplus` extends InfixOperator(9, 13, true)
  case object `?` extends InfixOperator(5, 5)
  case object `(/)` extends InfixOperator(13, 13)
  case object `\\geq` extends InfixOperator(5, 5)
  case object `(.)` extends InfixOperator(13, 13)
  case object `(\\X)` extends InfixOperator(13, 13)
  case object `//` extends InfixOperator(13, 13)
  case object `+` extends InfixOperator(10, 10, true)
  case object `<:` extends InfixOperator(7, 7)
  case object `\\doteq` extends InfixOperator(5, 5)
  case object `...` extends InfixOperator(9, 9)
  case object `&&` extends InfixOperator(13, 13, true)
  case object `\\otimes` extends InfixOperator(13, 13, true)
  case object `\\preceq` extends InfixOperator(5, 5)
  case object `\\wr` extends InfixOperator(9, 14)
  case object `\\gg` extends InfixOperator(5, 5)
  case object `--` extends InfixOperator(11, 11, true)
  case object `\\ll` extends InfixOperator(5, 5)
  case object `\\intersect` extends InfixOperator(8, 8)
  case object `\\sqsupset` extends InfixOperator(5, 5)
  case object `$` extends InfixOperator(9, 13, true)
  case object `\\cup` extends InfixOperator(8, 8, true)
  case object `(+)` extends InfixOperator(10, 10)
  case object `:=` extends InfixOperator(5, 5)
  case object `!!` extends InfixOperator(9, 13)
  case object `^` extends InfixOperator(14, 14)
  case object `\\star` extends InfixOperator(13, 13, true)
  case object `$$` extends InfixOperator(9, 13, true)
  case object `>` extends InfixOperator(5, 5)
  case object `_##_` extends InfixOperator(9, 13, true):
    override def spelling: String = "##"
  case object `-+->` extends InfixOperator(2, 2)
  case object `/` extends InfixOperator(13, 13)
  case object `\\sqsubset` extends InfixOperator(5, 5)
  case object `\\supset` extends InfixOperator(5, 5)
  case object `%%` extends InfixOperator(10, 11, true)

  sealed trait PostfixOperator(val predecence: Int) extends Operator
  object PostfixOperator extends util.HasInstanceArray[PostfixOperator]

  case object `^+` extends PostfixOperator(15)
  case object `^*` extends PostfixOperator(15)
  case object `^#` extends PostfixOperator(15)
  case object `'` extends PostfixOperator(15)
