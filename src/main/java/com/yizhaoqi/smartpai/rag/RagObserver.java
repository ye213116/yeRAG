package com.yizhaoqi.smartpai.rag;

import java.util.Map;
public interface RagObserver {
    default void progress(String stage) {}
    default void chunk(String text) {}
    default void references(Map<Integer,String> references) {}
}
