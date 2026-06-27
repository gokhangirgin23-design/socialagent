package com.api.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContentListRequest {
    private int page = 0;
    private int size = 10;
}
