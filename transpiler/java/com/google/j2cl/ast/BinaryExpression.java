/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.ast;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.j2cl.ast.annotations.Visitable;

/**
 * Binary operator expression.
 */
@Visitable
public class BinaryExpression extends Expression {
  private TypeDescriptor typeDescriptor;
  @Visitable Expression leftOperand;
  private BinaryOperator operator;
  @Visitable Expression rightOperand;

  private BinaryExpression(
      Expression leftOperand,
      BinaryOperator operator,
      Expression rightOperand) {
    this.leftOperand = checkNotNull(leftOperand);
    this.operator = checkNotNull(operator);
    this.rightOperand = checkNotNull(rightOperand);
    this.typeDescriptor =
        binaryOperationResultType(
            operator, leftOperand.getTypeDescriptor(), rightOperand.getTypeDescriptor());
  }

  public Expression getLeftOperand() {
    return leftOperand;
  }

  public BinaryOperator getOperator() {
    return operator;
  }

  public Expression getRightOperand() {
    return rightOperand;
  }

  @Override
  public TypeDescriptor getTypeDescriptor() {
    return typeDescriptor;
  }

  @Override
  public boolean isIdempotent() {
    return !operator.hasSideEffect() && leftOperand.isIdempotent() && rightOperand.isIdempotent();
  }

  @Override
  public BinaryExpression clone() {
    return newBuilder()
        .setLeftOperand(leftOperand.clone())
        .setOperator(operator)
        .setRightOperand(rightOperand.clone())
        .build();
  }

  @Override
  public Node accept(Processor processor) {
    return Visitor_BinaryExpression.visit(processor, this);
  }

  /** Determines the binary operation type based on the types of the operands. */
  private static TypeDescriptor binaryOperationResultType(
      BinaryOperator operator, TypeDescriptor leftOperandType, TypeDescriptor rightOperandType) {

    if (operator.isAssignmentOperator()) {
      return leftOperandType;
    }

    if (isStringConcatenation(operator, leftOperandType, rightOperandType)) {
      return TypeDescriptors.get().javaLangString;
    }

    switch (operator) {
        /*
         * Conditional operators: JLS 15.23.
         */
      case CONDITIONAL_AND:
      case CONDITIONAL_OR:
        /*
         * Relational operators: JLS 15.20.
         */
      case LESS:
      case LESS_EQUALS:
      case EQUALS:
      case NOT_EQUALS:
      case GREATER:
      case GREATER_EQUALS:
        return TypeDescriptors.get().primitiveBoolean;
      default:
    }

    leftOperandType = leftOperandType.unboxType();

    /**
     * Rules per JLS (Chapter 15) require that binary promotion be previously applied to the
     * operands and makes the operation to be the same type as both operands. Since this method is
     * potentially called before or while numeric promotion is being performed there is no guarantee
     * operand promotion was already performed; so that fact is taken into account.
     */
    switch (operator) {
        /*
         * Bitwise and logical operators: JLS 15.22.
         */
      case BIT_AND:
      case BIT_OR:
      case BIT_XOR:
        if (TypeDescriptors.isPrimitiveBoolean(leftOperandType)) {
          // Handle logical operations (on type boolean).
          return leftOperandType;
        }
        // fallthrough for bitwise operations on numbers.
        /*
         * Additive operators for numeric types: JLS 15.18.2.
         */
      case PLUS:
      case MINUS:
        /*
         * Multiplicative operators for numeric types: JLS 15.17.
         */
      case TIMES:
      case DIVIDE:
      case REMAINDER:
        /**
         * The type of the operation should the promoted type of the operands, which is equivalent
         * to the widest type of its operands (or integer is integer is wider).
         */
        checkArgument(TypeDescriptors.isBoxedOrPrimitiveType(rightOperandType));
        return widerType(leftOperandType, rightOperandType.unboxType());
      case LEFT_SHIFT:
      case RIGHT_SHIFT_SIGNED:
      case RIGHT_SHIFT_UNSIGNED:
        /**
         * Shift operators: JLS 15.19.
         *
         * <p>Type type of the operation is the type of the promoted left hand operand.
         */
        return leftOperandType;
      default:
        // All binary operators should have been handled.
        throw new IllegalStateException("Unhandled operator: " + operator);
    }
  }

  private static boolean isStringConcatenation(
      BinaryOperator operator, TypeDescriptor leftOperandType, TypeDescriptor rightOperandType) {
    if (operator != BinaryOperator.PLUS) {
      return false;
    }
    if (TypeDescriptors.isJavaLangString(leftOperandType)
        || TypeDescriptors.isJavaLangString(rightOperandType)) {
      return true;
    }

    return false;
  }

  /**
   * Numeric binary operations are typed at the wider type of its operands, or int if it is wider.
   */
  private static TypeDescriptor widerType(
      TypeDescriptor thisTypeDescriptor, TypeDescriptor thatTypeDescriptor) {
    TypeDescriptor widerTypeDescriptor =
        TypeDescriptors.getWidth(thatTypeDescriptor) > TypeDescriptors.getWidth(thisTypeDescriptor)
            ? thatTypeDescriptor
            : thisTypeDescriptor;

    // Arithmetic operations on integral types always result in int or long, whichever is wider.
    widerTypeDescriptor =
        TypeDescriptors.getWidth(TypeDescriptors.get().primitiveInt)
                > TypeDescriptors.getWidth(widerTypeDescriptor)
            ? TypeDescriptors.get().primitiveInt
            : widerTypeDescriptor;

    return widerTypeDescriptor;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * A Builder for binary expressions.
   */
  public static class Builder {
    private BinaryOperator operator;
    private Expression leftOperand;
    private Expression rightOperand;

    public static Builder from(BinaryExpression expression) {
      return new Builder()
          .setLeftOperand(expression.getLeftOperand())
          .setOperator(expression.getOperator())
          .setRightOperand(expression.getRightOperand());
    }

    public static Builder asAssignmentTo(Expression lvalue) {
      return new Builder().setLeftOperand(lvalue).setOperator(BinaryOperator.ASSIGN);
    }

    public static Builder asAssignmentTo(Field field) {
      return new Builder()
          .setLeftOperand(FieldAccess.Builder.from(field).build())
          .setOperator(BinaryOperator.ASSIGN);
    }

    public static Builder asAssignmentTo(FieldDescriptor fieldDescriptor) {
      return new Builder()
          .setLeftOperand(FieldAccess.Builder.from(fieldDescriptor).build())
          .setOperator(BinaryOperator.ASSIGN);
    }

    public static Builder asAssignmentTo(Variable variable) {
      return new Builder()
          .setLeftOperand(variable.getReference())
          .setOperator(BinaryOperator.ASSIGN);
    }

    public Builder setLeftOperand(Expression operand) {
      this.leftOperand = operand;
      return this;
    }

    public Builder setLeftOperand(Variable variable) {
      this.leftOperand = variable.getReference();
      return this;
    }

    public Builder setRightOperand(Expression operand) {
      this.rightOperand = operand;
      return this;
    }

    public Builder setRightOperand(Variable variable) {
      this.rightOperand = variable.getReference();
      return this;
    }

    public Builder setOperator(BinaryOperator operator) {
      this.operator = operator;
      return this;
    }

    public final BinaryExpression build() {
      return new BinaryExpression(leftOperand, operator, rightOperand);
    }
  }
}