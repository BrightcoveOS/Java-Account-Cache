package com.brightcove.account.cache.test;

import java.io.File;
import java.util.Set;
import java.util.logging.Logger;

import com.brightcove.account.cache.AccountCache;
import com.brightcove.account.cache.exceptions.AccountCacheException;
import com.brightcove.commons.account.objects.BrightcoveAccount;
import com.brightcove.commons.catalog.objects.enumerations.VideoStateFilterEnum;
import com.brightcove.commons.collection.CollectionUtils;
import com.brightcove.commons.system.commandLine.CommandLineProgram;
import com.brightcove.mediaapi.wrapper.ReadApi;

public class CacheTests extends CommandLineProgram {
	Logger log;
	
	/**
	 * <p>
	 *    Constructor
	 * </p>
	 */
	public CacheTests(){
		log = Logger.getLogger(this.getClass().getCanonicalName());
	}
	
	/**
	 * <p>
	 *    Main execution kickoff
	 * </p>
	 * 
	 * @param args Arguments passed in on command line
	 */
	public static void main(String[] args) {
		CacheTests ct  = new CacheTests();
		
		ct.allowNormalArgument("read-token", "--read-token <Read API Token>", "--read-token: Media API Token from Brightcove account allowing read access", true);
		ct.allowNormalArgument("cache-file", "--cache-file <Path>",           "--cache-file: Path to file on disk to store account cache",                  true);
		
		ct.setMaxNakedArguments(0);
		ct.setMinNakedArguments(0);
		
		ct.run(args);
	}
	
	/* (non-Javadoc)
	 * @see com.brightcove.commons.system.commandLine.CommandLineProgram#run(java.lang.String[])
	 */
	public void run(String[] args){
		setCaller(this.getClass().getCanonicalName());
		parseArguments(args);
		
		String readToken            = getNormalArgument("read-token");
		File   cacheFile            = new File(getNormalArgument("cacheFile"));
		
		log.info("Configuration:\n" +
			"\tRead token:             '" + readToken                   + "'.\n" + 
			"\tCache file:             '" + cacheFile.getAbsolutePath() + "'.\n");
		
		// Custom fields determine which custom fields to fill out on returned videos
		Set<String> customFields = CollectionUtils.CreateEmptyStringSet();
		
		// Video state filters determine which videos to return on a FindModifiedVideos call
		Set<VideoStateFilterEnum> videoStateFilters = VideoStateFilterEnum.CreateFullSet();
		
		// Instantiate a ReadApi wrapper object to make the actual calls
		ReadApi readApi = new ReadApi(log);
		
		// --------------------- Account Cache Methods ------------------
		
		Long              accountId = 123456789l;
		BrightcoveAccount account   = new BrightcoveAccount(accountId);
		AccountCache      cache     = new AccountCache(readApi, account);
		
		// Finish Here...
		try {
			cache.Deserialize(cacheFile);
			cache.ReadAccount(videoStateFilters, customFields);
			cache.Serialize(cacheFile);
		}
		catch (AccountCacheException ace) {
			usage(ace);
		}
	}
}
