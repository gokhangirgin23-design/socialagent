package com.api.common;

import lombok.Getter;
import lombok.Setter;

/**
 * Data taşıyan generic response. BaseResponse'tan extend eder.
 * Data yoksa 'data' alanı null döner (CLAUDE.md Madde 3).
 *
 * @param <T> dönen data tipi
 */
@Getter
@Setter
public class DataResponse<T> extends BaseResponse {

	// Asıl veri; data yoksa null
	private T data;

	public DataResponse() {
		super();
	}

	// Başarılı response üretmek için kısa yol: kod=success, data set edilir
	public static <T> DataResponse<T> success(T data) {
		DataResponse<T> response = new DataResponse<>();
		response.applyCode(ResponseCode.SUCCESS);
		response.setData(data);
		return response;
	}

	// Belirli bir kod ile data'sız (null) response üretmek için kısa yol
	public static <T> DataResponse<T> of(ResponseCode code) {
		DataResponse<T> response = new DataResponse<>();
		response.applyCode(code);
		response.setData(null);
		return response;
	}
}
