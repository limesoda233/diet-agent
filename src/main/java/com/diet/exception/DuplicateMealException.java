package com.diet.exception;

public class DuplicateMealException extends DietException {
    public DuplicateMealException() { super("个人餐食库中已有同名餐食，请确认是否合并标签"); }
}
