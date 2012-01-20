package com.brightcove.account.cache.exceptions;

/**
 * <p>
 *    Custom error codes that can be reported by the Account Cache
 * </p>
 * 
 * @author <a href="https://github.com/three4clavin">three4clavin</a>
 *
 */
public enum AccountCacheExceptionCode {
	// 900 Series: Account Cache errors
	ACCOUNT_CACHE_XML_READ_EXCEPTION(900,  "Exception caught trying to read or parse Account Cache XML"),
	ACCOUNT_CACHE_XML_WRITE_EXCEPTION(901, "Exception caught trying to write Account Cache XML"),
	ACCOUNT_CACHE_MISSING_PARAMETERS(902,  "Missing required data to create or use account cache"),
	ACCOUNT_CACHE_MISSING_FIELDS(903,      "Videos in cache are missing required fields");
	
	private final Integer code;
	private final String  description;
	AccountCacheExceptionCode(Integer code, String description){
		this.code        = code;
		this.description = description;
	}
	
	public String getDescription() {
		return description;
	}
	
	public Integer getCode() {
		return code;
	}
}
