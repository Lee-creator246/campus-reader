package org.campusreader.reader;
import java.util.*;

/** Literal phrases only. No regular expression or wildcard syntax. */
public final class KeywordTerms {
    private KeywordTerms(){}
    public static List<String> parse(String text){if(text==null)throw new IllegalArgumentException("请输入关键词");return clean(Arrays.asList(text.split("[,，\\r\\n]+")));}
    public static List<String> clean(Collection<String> words){
        if(words==null)throw new IllegalArgumentException("关键词不能为空");
        LinkedHashMap<String,String> distinct=new LinkedHashMap<>();
        for(String word:words){if(word==null)throw new IllegalArgumentException("关键词不能为空");
            for(int i=0;i<word.length();i++)if(Character.isISOControl(word.charAt(i)))throw new IllegalArgumentException("关键词不能包含控制字符");
            String value=word.trim();if(value.isEmpty())continue;if(value.codePointCount(0,value.length())>100)throw new IllegalArgumentException("每个关键词最多 100 个字");
            StringBuilder folded=new StringBuilder(value);for(int i=0;i<folded.length();i++){char c=folded.charAt(i);if(c>='A'&&c<='Z')folded.setCharAt(i,(char)(c+32));}
            if(!distinct.containsKey(folded.toString()))distinct.put(folded.toString(),value);
            if(distinct.size()>50)throw new IllegalArgumentException("每组最多保存 50 个关键词");
        }
        return new ArrayList<>(distinct.values());
    }
}
