package com.brightcove.account.cache.exceptions;

/**
 * <p>
 *    An account cache exception.
 * </p>
 * 
 * @author <a href="https://github.com/three4clavin">three4clavin</a>
 *
 */
public class AccountCacheException extends Exception {
	private static final long serialVersionUID = 7669016192642078993L;
	
	private AccountCacheExceptionCode code;
	private String                    message;
	
	/**
	 * <p>
	 *    Creates an account cache exception.
	 * </p>
	 * 
	 * @param code Error code reported
	 * @param message Detailed message reported
	 */
	public AccountCacheException(AccountCacheExceptionCode code, String message) {
		super();
		this.code    = code;
		this.message = message;
	}
	
	/**
	 * <p>
	 *    Gets the error code reported
	 * </p>
	 * 
	 * @return Error code reported
	 */
	public AccountCacheExceptionCode getCode(){
		return code;
	}
	
	/**
	 * <p>
	 *    Gets the detailed message reported
	 * </p>
	 * 
	 * @return Message reported
	 */
	public String getMessage(){
		return message;
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Throwable#toString()
	 */
	public String toString(){
		return "[" + this.getClass().getCanonicalName() + "] (" + code.getCode() + ": " + code.getDescription() + ") Message: '" + message + "'";
	}
}
