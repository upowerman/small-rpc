package io.github.upowerman.dto;

import java.io.Serializable;

/**
 * @author gaoyunfeng
 */
public class HelloDTO implements Serializable {
    private static final long serialVersionUID = 40L;

    private String name;
    private String word;

    public HelloDTO(){

    }

    public HelloDTO(String name, String word) {
        this.name = name;
        this.word = word;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    @Override
    public String toString() {
        return "HelloDTO{" +
                "name='" + name + '\'' +
                ", word='" + word + '\'' +
                '}';
    }
}
