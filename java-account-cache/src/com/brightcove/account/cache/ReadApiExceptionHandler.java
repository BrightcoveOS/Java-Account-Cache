package com.brightcove.account.cache;

import com.brightcove.mediaapi.exceptions.BrightcoveException;
import com.brightcove.mediaapi.exceptions.BrightcoveExceptionHandler;

/**
 * <p>
 *    Implements a basic exception handler for Read API calls
 * </p>
 * 
 * <p>
 *    This may not be thread safe...
 * </p>
 *
 */
public class ReadApiExceptionHandler implements BrightcoveExceptionHandler {
	private Integer currentTry = 0;
	private Integer maxTries   = 20;
	private Long    tryDelay   = 60000l;
	
	public Boolean handleException(BrightcoveException be, String methodName) throws BrightcoveException {
		if("FindModifiedVideos".equals(methodName)){
			currentTry++;
			
			if(currentTry > maxTries){
				return false;
			}
			
			try{ Thread.sleep(tryDelay); } catch(Exception e) {}
			
			return true;
		}
		
		// Don't try to handle exceptions from any other methods
		return false;
	}
	
	public void resetTryCounter(){
		this.currentTry = 0;
	}
	
	public void setMaxTries(Integer maxTries){
		this.maxTries = maxTries;
	}
	
	public void setTryDelay(Long tryDelay){
		this.tryDelay = tryDelay;
	}
	
	public Integer getTryCount(){
		return currentTry;
	}
	
	public Integer getMaxTries(){
		return maxTries;
	}
	
	public Long getTryDelay(){
		return tryDelay;
	}
}
