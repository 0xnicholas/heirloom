package com.heirloom.alignment.service;

import com.heirloom.core.alignment.*;
import com.heirloom.core.metadata.ColumnDef;
import com.heirloom.metadata.domain.*;
import com.heirloom.repository.*;
import com.heirloom.schema.domain.ResourceType;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AlignmentServiceImpl implements AlignmentService {

    private final TypeRepository typeRepository;

    public AlignmentServiceImpl(TypeRepository typeRepository) {
        this.typeRepository = typeRepository;
    }

    @Override
    public AlignmentMap align(AlignmentRequest request) {
        List<FieldAlignment> alignments = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();

        List<ResourceType> knownTypes = typeRepository.findAll();

        return new AlignmentMap(request.tableFQN(), alignments, unmapped, List.of());
    }

    public static boolean isNameSimilar(String colName, String fieldName) {
        String a = colName.toLowerCase().replace("_", "");
        String b = fieldName.toLowerCase().replace("_", "");
        if (a.equals(b)) return true;
        if (a.contains(b) || b.contains(a)) return true;
        return editDistance(a, b) < 3;
    }

    private static int editDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                    ? dp[i - 1][j - 1]
                    : 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
            }
        }
        return dp[a.length()][b.length()];
    }
}
